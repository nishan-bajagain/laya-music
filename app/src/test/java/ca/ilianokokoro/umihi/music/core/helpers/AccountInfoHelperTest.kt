package ca.ilianokokoro.umihi.music.core.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [AccountInfoHelper.parseAccountInfo]. The avatar path
 * is the fragile part: name/email parse from stable "runs" text while the
 * account photo has lived under several shapes in YouTube's scraped
 * account_menu response. Every fixture here is a realistic response skeleton;
 * the discriminating cases (direct url, nested thumbnail wrapper, image
 * sources, plain string, renamed key, largest-by-dimension) all fail against
 * the old `accountPhoto.thumbnails[last].url`-only parser.
 */
class AccountInfoHelperTest {

    @Test
    fun `classic thumbnails response parses name email and avatar`() {
        val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu()
        )
        assertEquals("Laya User", name)
        assertEquals("laya.user@gmail.com", email)
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `direct url on accountPhoto is extracted`() {
        // Fails on the old parser: no "thumbnails" array.
        val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu(
                """{"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c", "width": 96, "height": 96}"""
            )
        )
        assertEquals("Laya User", name)
        assertEquals("laya.user@gmail.com", email)
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `nested thumbnail wrapper is extracted`() {
        // Fails on the old parser: photo wrapped in "thumbnail".
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu(
                """{"thumbnail": {"thumbnails": [{"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c", "width": 96, "height": 96}]}}"""
            )
        )
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `image sources shape is extracted`() {
        // Fails on the old parser: newest "image renderer" pattern.
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu(
                """{"image": {"sources": [{"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c", "width": 96, "height": 96}]}}"""
            )
        )
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `largest thumbnail wins regardless of array order`() {
        // Descending order: the old lastOrNull() would pick the 32px entry.
        // Distinct URL paths make the chosen entry observable after the
        // size suffix is normalized away.
        val descending = classicAccountMenu(
            """{"thumbnails": [
                {"url": "https://lh3.googleusercontent.com/a/BIG=s96-c", "width": 96, "height": 96},
                {"url": "https://lh3.googleusercontent.com/a/SMALL=s32-c", "width": 32, "height": 32}
            ]}"""
        )
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(descending)
        assertEquals("https://lh3.googleusercontent.com/a/BIG=s256-c", avatar)
    }

    @Test
    fun `plain string accountPhoto is extracted`() {
        // Fails on the old parser: accountPhoto as string throws a
        // ClassCastException inside the old chain.
        val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu("\"https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c\"")
        )
        assertEquals("Laya User", name)
        assertEquals("laya.user@gmail.com", email)
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `renamed photo key is extracted`() {
        // Fails on the old parser: key is "accountAvatar", not "accountPhoto".
        val json = classicAccountMenu()
            .replace("\"accountPhoto\"", "\"accountAvatar\"")
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(json)
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `protocol relative url is upgraded to https and larger size`() {
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu(
                """{"thumbnails": [{"url": "//lh3.googleusercontent.com/a/ACg8ocLx9=s96-c", "width": 96, "height": 96}]}"""
            )
        )
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `size suffix with trailing variant is upgraded`() {
        val (_, _, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu(
                """{"thumbnails": [{"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c-k-no", "width": 96, "height": 96}]}"""
            )
        )
        assertEquals("https://lh3.googleusercontent.com/a/ACg8ocLx9=s256-c", avatar)
    }

    @Test
    fun `unknown avatar shape still yields name and email`() {
        val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(
            classicAccountMenu("""{"someFutureShape": [{"id": 1}]}""")
        )
        assertEquals("Laya User", name)
        assertEquals("laya.user@gmail.com", email)
        assertEquals("", avatar)
    }

    @Test
    fun `root header fallback path is parsed`() {
        val json = """
            {
              "header": {
                "activeAccountHeaderRenderer": {
                  "accountName": {"runs": [{"text": "Alt User"}]},
                  "email": {"runs": [{"text": "alt@gmail.com"}]},
                  "accountPhoto": {
                    "thumbnails": [
                      {"url": "https://lh3.googleusercontent.com/a/ABC=s96-c", "width": 96, "height": 96}
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(json)
        assertEquals("Alt User", name)
        assertEquals("alt@gmail.com", email)
        assertEquals("https://lh3.googleusercontent.com/a/ABC=s256-c", avatar)
    }

    @Test
    fun `unparseable input returns blanks without throwing`() {
        for (bad in listOf("", "not json", "{}", """{"actions": []}""")) {
            val (name, email, avatar) = AccountInfoHelper.parseAccountInfo(bad)
            assertEquals("", name)
            assertEquals("", email)
            assertEquals("", avatar)
        }
    }

    /** Minimal realistic account_menu skeleton with a replaceable photo object. */
    private fun classicAccountMenu(photoJson: String = DEFAULT_PHOTO): String = """
        {
          "actions": [
            {
              "openPopupAction": {
                "popup": {
                  "multiPageMenuRenderer": {
                    "header": {
                      "activeAccountHeaderRenderer": {
                        "accountName": {"runs": [{"text": "Laya User"}]},
                        "email": {"runs": [{"text": "laya.user@gmail.com"}]},
                        "accountPhoto": $photoJson
                      }
                    }
                  }
                }
              }
            }
          ]
        }
    """.trimIndent()

    private companion object {
        val DEFAULT_PHOTO = """
            {
              "thumbnails": [
                {"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s32-c", "width": 32, "height": 32},
                {"url": "https://lh3.googleusercontent.com/a/ACg8ocLx9=s96-c", "width": 96, "height": 96}
              ]
            }
        """.trimIndent()
    }
}
