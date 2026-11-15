#!/usr/bin/env python3
"""Sign or verify an APK with APK Signature Scheme v2, using only Python.

Why this exists
---------------
`apksigner` streams the signed APK through a temporary buffer. On some
Android/Termux runtimes that copy is silently cut short near 130 MB, which
leaves a ZIP with no end-of-central-directory record. The archive is then
unreadable and uninstallable even though the signer exits 0. `jarsigner`
writes the same file correctly, so the problem is confined to the large-file
copy, not to the package itself.

This script inserts the v2 signing block directly and writes the output
sequentially. Entry data and central directory are copied byte for byte, so
alignment (resources.arsc at 4 bytes) is preserved.

Only v2 is produced. minSdkVersion is 24 and Android 11+ refuses a v1-only
APK when targetSdkVersion >= 30, so v2 is the scheme that matters. v3 is
optional on top of v2 and only carries key-rotation metadata this project
does not use.

Format reference: AOSP `ApkSigningBlockUtils` and `V2SchemeSigner`.

Usage
-----
    sign-apk-v2.py sign \\
        --key private.pk8 --cert cert.der --public public.der \\
        --in unsigned.apk --out signed.apk

    sign-apk-v2.py verify signed.apk

The key files are raw DER encodings dumped from the JKS keystore, for example
with the small `DumpSigningKey` helper used by scripts/build-apk.sh.
"""

import argparse
import hashlib
import struct
import sys

V2_BLOCK_ID = 0x7109871A
CHUNK_SIZE = 1024 * 1024
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_SIGNATURE = b"PK\x05\x06"

# signature algorithm ID -> (JCA digest name, DigestInfo prefix)
SIGNATURE_ALGORITHMS = {
    0x0103: ("sha256",
             bytes.fromhex("3031300d060960864801650304020105000420")),
    0x0104: ("sha512",
             bytes.fromhex("3051300d060960864801650304020305000440")),
}
# apksigner pairs a 4096-bit key with SHA-512 rather than SHA-256.
DEFAULT_SIGNATURE_ALGORITHM = 0x0104


class ApkError(Exception):
    pass


def lp(value):
    """Length-prefix a byte string with a little-endian uint32."""
    return struct.pack("<I", len(value)) + value


def read_der(data, pos):
    """Read one DER TLV. Returns (tag, value, next_position)."""
    if pos + 2 > len(data):
        raise ApkError("truncated DER")
    tag = data[pos]
    length = data[pos + 1]
    pos += 2
    if length & 0x80:
        count = length & 0x7F
        if pos + count > len(data):
            raise ApkError("truncated DER length")
        length = int.from_bytes(data[pos:pos + count], "big")
        pos += count
    end = pos + length
    if end > len(data):
        raise ApkError("truncated DER value")
    return tag, data[pos:end], end


def der_items(data):
    """Split a DER SEQUENCE body into its child TLVs."""
    pos = 0
    while pos < len(data):
        tag, value, pos = read_der(data, pos)
        yield tag, value


def parse_pkcs8_private_key(data):
    """Return (modulus, private_exponent) from a PKCS#8 RSAPrivateKey."""
    tag, outer, _ = read_der(data, 0)
    if tag != 0x30:
        raise ApkError("private key is not a DER SEQUENCE")
    items = list(der_items(outer))
    if len(items) < 3 or items[1][0] != 0x30 or items[2][0] != 0x04:
        raise ApkError("unsupported PKCS#8 layout")
    tag, pkcs1, _ = read_der(items[2][1], 0)
    if tag != 0x30:
        raise ApkError("private key is not RSA")
    integers = [int.from_bytes(value, "big")
                for tag, value in der_items(pkcs1) if tag == 0x02]
    if len(integers) < 4:
        raise ApkError("truncated RSA private key")
    return integers[1], integers[3]


def parse_public_key(data):
    """Return (modulus, exponent) from a SubjectPublicKeyInfo DER."""
    tag, outer, _ = read_der(data, 0)
    if tag != 0x30:
        raise ApkError("public key is not a DER SEQUENCE")
    items = list(der_items(outer))
    if len(items) < 2 or items[1][0] != 0x03:
        raise ApkError("unsupported SubjectPublicKeyInfo layout")
    bit_string = items[1][1]
    if not bit_string or bit_string[0] != 0:
        raise ApkError("unexpected bit string padding")
    tag, rsa, _ = read_der(bit_string[1:], 0)
    if tag != 0x30:
        raise ApkError("public key is not RSA")
    integers = [int.from_bytes(value, "big")
                for tag, value in der_items(rsa) if tag == 0x02]
    if len(integers) < 2:
        raise ApkError("truncated RSA public key")
    return integers[0], integers[1]


def find_eocd(data):
    """Locate the end-of-central-directory record and return its offset."""
    start = max(0, len(data) - 65557)
    for pos in range(len(data) - 22, start - 1, -1):
        if data[pos:pos + 4] != EOCD_SIGNATURE:
            continue
        comment_length = struct.unpack_from("<H", data, pos + 20)[0]
        if pos + 22 + comment_length == len(data):
            return pos
    raise ApkError("ZIP end of central directory record not found")


def eocd_fields(data, eocd_offset):
    cd_size, cd_offset = struct.unpack_from("<II", data, eocd_offset + 12)
    return cd_size, cd_offset


def chunked_content_digest(segments, algorithm):
    """APK Signature Scheme v2 content digest over the given segments.

    Every non-empty segment is split into 1 MB chunks; the chunk digests of all
    segments are concatenated in order, then hashed as
    0x5a || uint32le(chunk_count) || chunk_digests.
    """
    chunk_digests = []
    for segment in segments:
        view = memoryview(segment)
        for pos in range(0, len(view), CHUNK_SIZE):
            chunk = view[pos:pos + CHUNK_SIZE]
            digest = hashlib.new(algorithm)
            digest.update(b"\xa5")
            digest.update(struct.pack("<I", len(chunk)))
            digest.update(chunk)
            chunk_digests.append(digest.digest())

    top = hashlib.new(algorithm)
    top.update(b"\x5a")
    top.update(struct.pack("<I", len(chunk_digests)))
    for digest in chunk_digests:
        top.update(digest)
    return top.digest()


def signing_block(pairs):
    """Wrap ID-value pairs in the APK Signing Block envelope."""
    # Each pair's uint64 length covers the 4-byte ID and the value.
    body = b"".join(struct.pack("<Q", len(value) + 4)
                    + struct.pack("<I", block_id) + value
                    for block_id, value in pairs)
    size = len(body) + 8 + 16
    return (struct.pack("<Q", size) + body + struct.pack("<Q", size)
            + APK_SIG_BLOCK_MAGIC)


def algorithm_record(algorithm, value):
    """Encode a v2 digest/signature record: length-prefixed, then ID + bytes."""
    payload = struct.pack("<II", algorithm, len(value)) + value
    return lp(payload)


def rsa_sign(signed_data, modulus, exponent, algorithm):
    """RSASSA-PKCS1-v1_5 signature over `signed_data`."""
    _, digest_info_prefix = SIGNATURE_ALGORITHMS[algorithm]
    digest_info = digest_info_prefix + hashlib.new(
        SIGNATURE_ALGORITHMS[algorithm][0], signed_data).digest()
    key_bytes = (modulus.bit_length() + 7) // 8
    padding = key_bytes - 3 - len(digest_info)
    if padding < 8:
        raise ApkError("RSA key too small for the selected digest")
    encoded = b"\x00\x01" + b"\xff" * padding + b"\x00" + digest_info
    signature = pow(int.from_bytes(encoded, "big"), exponent, modulus)
    return signature.to_bytes(key_bytes, "big")


def build_v2_value(content_digest, cert_der, public_key_der, signature,
                   algorithm):
    """Build the v2 block payload: one signer with one signature."""
    digest_records = algorithm_record(algorithm, content_digest)
    certificate_records = lp(cert_der)
    # Additional attributes are empty: the stripping-protection attribute only
    # matters when a v3 block is also present.
    signed_data = (lp(digest_records)
                   + lp(certificate_records)
                   + lp(b""))
    signature_records = algorithm_record(algorithm, signature)
    signer = lp(signed_data) + lp(signature_records) + lp(public_key_der)
    # Value = length-prefixed sequence of length-prefixed signers.
    return lp(lp(signer)), signed_data


def sign(apk_in, apk_out, key_path, cert_path, public_path,
         algorithm=DEFAULT_SIGNATURE_ALGORITHM):
    if algorithm not in SIGNATURE_ALGORITHMS:
        raise ApkError("unsupported signature algorithm 0x%x" % algorithm)
    with open(apk_in, "rb") as handle:
        data = handle.read()
    with open(key_path, "rb") as handle:
        modulus, private_exponent = parse_pkcs8_private_key(handle.read())
    with open(cert_path, "rb") as handle:
        cert_der = handle.read()
    with open(public_path, "rb") as handle:
        public_key_der = handle.read()

    eocd_offset = find_eocd(data)
    cd_size, cd_offset = eocd_fields(data, eocd_offset)
    if cd_offset + cd_size != eocd_offset:
        raise ApkError("central directory does not end at the EOCD record")

    # The block size depends only on fixed-length fields, so a placeholder
    # tells us the final layout before the EOCD digest is computed.
    key_bytes = (modulus.bit_length() + 7) // 8
    digest_length = hashlib.new(SIGNATURE_ALGORITHMS[algorithm][0]).digest_size
    placeholder, _ = build_v2_value(b"\x00" * digest_length, cert_der,
                                    public_key_der, b"\x00" * key_bytes,
                                    algorithm)
    block_total = len(signing_block([(V2_BLOCK_ID, placeholder)]))
    new_cd_offset = cd_offset + block_total

    # v2 hashes the EOCD as though its central directory offset still pointed
    # at the start of the signing block, so the original record is what gets
    # digested. The final copy written below points past the block instead.
    eocd = bytearray(data[eocd_offset:])
    digest_eocd = bytes(eocd)
    struct.pack_into("<I", eocd, 16, new_cd_offset)

    view = memoryview(data)
    content_digest = chunked_content_digest(
        [view[0:cd_offset], view[cd_offset:cd_offset + cd_size], digest_eocd],
        SIGNATURE_ALGORITHMS[algorithm][0])
    _, signed_data = build_v2_value(content_digest, cert_der,
                                    public_key_der, b"\x00" * key_bytes,
                                    algorithm)
    signature = rsa_sign(signed_data, modulus, private_exponent, algorithm)
    value, signed_data = build_v2_value(content_digest, cert_der,
                                        public_key_der, signature, algorithm)
    block = signing_block([(V2_BLOCK_ID, value)])
    if len(block) != block_total:
        raise ApkError("signing block size changed between passes")

    with open(apk_out, "wb") as handle:
        handle.write(view[0:cd_offset])
        handle.write(block)
        handle.write(view[cd_offset:eocd_offset])
        handle.write(bytes(eocd))

    print("signed %s" % apk_out)
    print("  entries        %d bytes" % cd_offset)
    print("  signing block  %d bytes" % block_total)
    print("  central dir    %d bytes at %d" % (cd_size, new_cd_offset))
    print("  signature      RSA PKCS#1 v1.5 with %s"
          % SIGNATURE_ALGORITHMS[algorithm][0].upper())


def parse_v2_block(data):
    eocd_offset = find_eocd(data)
    cd_size, cd_offset = eocd_fields(data, eocd_offset)
    if cd_offset < 24 or cd_offset + cd_size != eocd_offset:
        raise ApkError("not a ZIP with a central directory and signing block")
    size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    if data[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        raise ApkError("APK is not signed with a v2/v3 block")
    block_start = cd_offset - 8 - size
    if block_start < 0 or struct.unpack_from("<Q", data, block_start)[0] != size:
        raise ApkError("malformed APK Signing Block size fields")
    pos = block_start + 8
    end = cd_offset - 24
    while pos < end:
        length = struct.unpack_from("<Q", data, pos)[0]
        block_id = struct.unpack_from("<I", data, pos + 8)[0]
        if block_id == V2_BLOCK_ID:
            return block_start, data[pos + 12:pos + 8 + length], \
                eocd_offset, cd_offset
        pos += 8 + length
    raise ApkError("APK has a signing block but no v2 signature")


def read_lp(data, pos):
    length = struct.unpack_from("<I", data, pos)[0]
    return data[pos + 4:pos + 4 + length], pos + 4 + length


def read_records(data):
    """Read a v2 sequence: length-prefixed records until the slice ends."""
    records = []
    pos = 0
    while pos < len(data):
        value, pos = read_lp(data, pos)
        records.append(value)
    return records


def read_algorithm_records(blob):
    """Parse digest/signature records: uint32 ID + length-prefixed value."""
    records = []
    for element in read_records(blob):
        if len(element) < 8:
            raise ApkError("truncated v2 record")
        algorithm, length = struct.unpack_from("<II", element, 0)
        if length != len(element) - 8:
            raise ApkError("v2 record length does not match its payload")
        records.append((algorithm, element[8:]))
    return records


def verify(apk_path):
    with open(apk_path, "rb") as handle:
        data = handle.read()
    block_start, value, eocd_offset, cd_offset = parse_v2_block(data)

    signers_blob, _ = read_lp(value, 0)
    signers = read_records(signers_blob)
    if len(signers) != 1:
        raise ApkError("expected exactly one signer, found %d" % len(signers))
    signer = signers[0]
    signed_data, pos = read_lp(signer, 0)
    signatures_blob, pos = read_lp(signer, pos)
    public_key_der, pos = read_lp(signer, pos)
    if pos != len(signer):
        raise ApkError("trailing bytes in the v2 signer block")

    digest_records, pos = read_lp(signed_data, 0)
    certificates_blob, pos = read_lp(signed_data, pos)
    read_lp(signed_data, pos)  # additional attributes, unused here
    stored = read_algorithm_records(digest_records)
    certificates = read_records(certificates_blob)
    signatures = read_algorithm_records(signatures_blob)

    if len(stored) != 1 or len(signatures) != 1:
        raise ApkError("expected one digest and one signature, found %d/%d"
                       % (len(stored), len(signatures)))
    algorithm, stored_digest = stored[0]
    signature_algorithm, signature = signatures[0]
    if algorithm != signature_algorithm:
        raise ApkError("digest and signature use different algorithms")
    if algorithm not in SIGNATURE_ALGORITHMS:
        raise ApkError("unsupported signature algorithm 0x%x" % algorithm)
    digest_name, digest_info_prefix = SIGNATURE_ALGORITHMS[algorithm]

    # Verifiers digest the EOCD as though its central directory offset pointed
    # at the signing block, not at the moved central directory.
    digest_eocd = bytearray(data[eocd_offset:])
    struct.pack_into("<I", digest_eocd, 16, block_start)
    view = memoryview(data)
    computed = chunked_content_digest(
        [view[0:block_start], view[cd_offset:eocd_offset], digest_eocd],
        digest_name)
    if stored_digest != computed:
        raise ApkError("content digest does not match the archive")

    modulus, exponent = parse_public_key(public_key_der)
    key_bytes = (modulus.bit_length() + 7) // 8
    encoded = pow(int.from_bytes(signature, "big"),
                  exponent, modulus).to_bytes(key_bytes, "big")
    digest_info = digest_info_prefix + hashlib.new(digest_name,
                                                   signed_data).digest()
    expected = (b"\x00\x01" + b"\xff" * (key_bytes - 3 - len(digest_info))
                + b"\x00" + digest_info)
    if encoded != expected:
        raise ApkError("RSA signature does not verify")

    if not certificates:
        raise ApkError("signer has no certificate")

    print("v2 signature OK: %s" % apk_path)
    print("  signer key      %d-bit RSA" % modulus.bit_length())
    print("  signature       RSA PKCS#1 v1.5 with %s" % digest_name.upper())
    print("  certificate     %s… (%d bytes)"
          % (hashlib.sha256(certificates[0]).hexdigest()[:16],
             len(certificates[0])))
    print("  digests         verified against entries, central directory, EOCD")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    signer = sub.add_parser("sign", help="insert a v2 signature")
    signer.add_argument("--in", dest="apk_in", required=True)
    signer.add_argument("--out", dest="apk_out", required=True)
    signer.add_argument("--key", required=True, help="PKCS#8 RSA private key (DER)")
    signer.add_argument("--cert", required=True, help="X.509 certificate (DER)")
    signer.add_argument("--public", required=True, help="SubjectPublicKeyInfo (DER)")
    signer.add_argument("--algorithm", choices=("sha256", "sha512"),
                        default="sha512", help="RSA digest (default: sha512)")
    checker = sub.add_parser("verify", help="verify a v2 signature")
    checker.add_argument("apk")
    args = parser.parse_args(argv)
    try:
        if args.command == "sign":
            algorithm = {"sha256": 0x0103, "sha512": 0x0104}[args.algorithm]
            sign(args.apk_in, args.apk_out, args.key, args.cert, args.public,
                 algorithm)
        else:
            verify(args.apk)
    except ApkError as error:
        print("error: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
