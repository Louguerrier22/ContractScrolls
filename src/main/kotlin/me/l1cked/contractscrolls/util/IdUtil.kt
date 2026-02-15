package me.l1cked.contractscrolls.util

import java.security.SecureRandom
import java.time.Year
import java.time.ZoneId
import java.util.Locale

object IdUtil {
    private val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()
    private val random = SecureRandom()

    fun newContractId(zoneId: ZoneId): String {
        val year = Year.now(zoneId).value
        val suffix = StringBuilder(5)
        repeat(5) {
            suffix.append(chars[random.nextInt(chars.size)])
        }
        return "CS-$year-$suffix"
    }

    fun newNonce(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b))
        }
        return out.toString()
    }
}
