package graphrag.core.utils

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * Utility functions for hashing and checksums.
 */
object HashUtils {

  /**
   * Compute SHA-256 hash of a string
   */
  def sha256(text: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.map("%02x".format(_)).mkString
  }

  /**
   * Compute short SHA-256 hash (first 16 chars)
   */
  def sha256Short(text: String): String = sha256(text).take(16)

  /**
   * Compute MD5 hash of a string
   */
  def md5(text: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.map("%02x".format(_)).mkString
  }

  /**
   * Compute content hash for deduplication
   */
  def contentHash(content: String): String = {
    val normalized = content
      .toLowerCase
      .replaceAll("\\s+", " ")
      .trim
    sha256Short(normalized)
  }

  /**
   * Compute hash of multiple strings combined
   */
  def combinedHash(parts: String*): String = {
    sha256(parts.mkString(":"))
  }

  /**
   * Check if two texts have the same content hash
   */
  def sameContent(a: String, b: String): Boolean = {
    contentHash(a) == contentHash(b)
  }
}