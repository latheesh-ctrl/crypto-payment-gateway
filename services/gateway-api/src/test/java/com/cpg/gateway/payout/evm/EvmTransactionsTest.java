package com.cpg.gateway.payout.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.gateway.payout.DevKeySigner;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvmTransactionsTest {

    private static final HexFormat HEX = HexFormat.of();

    /** The worked example in EIP-155 itself. */
    @Test
    void signsTheEip155SpecificationExample() {
        DevKeySigner signer = new DevKeySigner("0x" + "46".repeat(32));
        EvmTransactions.Unsigned tx = EvmTransactions.nativeTransfer(1, 9, new BigInteger("20000000000"), 21000,
                "0x" + "35".repeat(20), new BigInteger("1000000000000000000"));

        assertThat(HEX.formatHex(EvmTransactions.signingHash(tx)))
                .isEqualTo("daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53");
        EvmTransactions.Signed signed = EvmTransactions.sign(tx, signer);
        assertThat(signed.rawTx()).isEqualTo("0xf86c098504a817c800825208943535353535353535353535353535353535353535880"
                + "de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f7"
                + "61aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83");
        assertThat(signed.txHash()).isEqualTo("0x" + HEX.formatHex(EvmTransactions.keccak256(HEX.parseHex(signed.rawTx().substring(2)))));
        assertThat(signer.address()).isEqualTo("0x9d8A62f656a8d1615C1294fd71e9CFb3E4855A4F");
    }

    @Test
    void encodesErc20TransferCallData() {
        byte[] data = EvmTransactions.transferData("0x" + "ab".repeat(20), BigInteger.valueOf(1_000_000));
        assertThat(HEX.formatHex(data)).isEqualTo("a9059cbb"
                + "000000000000000000000000" + "ab".repeat(20)
                + "00000000000000000000000000000000000000000000000000000000000f4240");

        EvmTransactions.Unsigned tx = EvmTransactions.tokenTransfer(42161, 0, BigInteger.TEN, 90_000,
                "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "0x" + "ab".repeat(20), BigInteger.ONE);
        assertThat(tx.value()).isZero();
        assertThat(tx.to()).isEqualTo("0xaf88d065e77c8cC2239327C5EDb3A432268e5831");
        // Large chain IDs push v past one byte; the transaction still encodes and signs.
        assertThat(EvmTransactions.sign(tx, new DevKeySigner("01".repeat(32))).rawTx()).startsWith("0xf8");
    }

    @Test
    void rejectsMalformedInputs() {
        assertThatThrownBy(() -> EvmTransactions.transferData("0x1234", BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvmTransactions.transferData("0x" + "ab".repeat(20), BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevKeySigner("not-a-key")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rlpMatchesTheSpecificationExamples() {
        assertThat(HEX.formatHex(Rlp.bytes("dog".getBytes()))).isEqualTo("83646f67");
        assertThat(HEX.formatHex(Rlp.list(List.of(Rlp.bytes("cat".getBytes()), Rlp.bytes("dog".getBytes())))))
                .isEqualTo("c88363617483646f67");
        assertThat(HEX.formatHex(Rlp.bytes(new byte[0]))).isEqualTo("80");
        assertThat(HEX.formatHex(Rlp.uint(0))).isEqualTo("80");
        assertThat(HEX.formatHex(Rlp.uint(15))).isEqualTo("0f");
        assertThat(HEX.formatHex(Rlp.uint(1024))).isEqualTo("820400");
        assertThat(HEX.formatHex(Rlp.list(List.of()))).isEqualTo("c0");
        byte[] lorem = "Lorem ipsum dolor sit amet, consectetur adipisicing elit".getBytes();
        assertThat(HEX.formatHex(Rlp.bytes(lorem))).startsWith("b838");
    }
}
