package app.yoru
import org.junit.Assert.assertEquals
import org.junit.Test
class EpisodeTest {
 @Test fun preservesSourceNamesAndFractionalNumbers() {
  assertEquals("Chapter 1 · The journey",Episode("The journey","x",mapOf("episode_number" to 1.0)).label(true))
  assertEquals("Chapter 1: The journey",Episode("Chapter 1: The journey","x",mapOf("episode_number" to 1.0)).label(true))
  assertEquals("Episode 1.5 · Interlude",Episode("Interlude","x",mapOf("episode_number" to 1.5)).label(false))
  assertEquals("Special",Episode("Special","x",mapOf("episode_number" to -1)).label(false))
 }
}
