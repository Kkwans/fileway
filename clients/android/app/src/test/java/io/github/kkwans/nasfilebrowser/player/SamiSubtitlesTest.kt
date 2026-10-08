package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class SamiSubtitlesTest {
    @Test fun languagesHaveIndependentTimesAndBlankEventsEndEarlierCaptions() {
        val tracks = SamiSubtitles.parse("""<SAMI><HEAD><STYLE><!-- .EN {Name:"English";lang:en-US;SAMI_Type:CC;} .KO {Name:Korean;lang:ko-KR;} --></STYLE></HEAD><BODY>
            <SYNC START="1000"><P CLASS=EN>Hello <b>world</b><BR>again<P Class='KO'>안녕
            <SYNC Start=2000><P Class=KO>다음
            <SYNC Start=3000><P Class=EN>&nbsp;<P Class=KO>&nbsp;
            </BODY></SAMI>""")
        assertEquals(listOf("English", "Korean"), tracks.map { it.title })
        assertEquals("en-US", tracks.first().language)
        assertEquals(2000L, tracks.first().captions.first().durationMs)
        assertEquals(1000L, tracks.last().captions.first().durationMs)
        assertTrue(tracks.first().captions.first().html.contains("<BR>"))
        assertEquals("&nbsp;", tracks.first().captions.last().html)
        assertNull(tracks.first().captions.last().durationMs)
    }
    @Test fun sourceSpeakerPersistsUntilExplicitUpdateAndCommonParagraphAppliesToAll() {
        val tracks = SamiSubtitles.parse("""<SYNC Start=0><P Class=EN ID=Source>Alice<P Class=EN>Hello<P Class=ZH>你好
            <SYNC Start=1000><P Class=EN>Next<P Class=ZH>下一句
            <SYNC Start=2000><P Class=EN ID=Source>&nbsp;<P>&nbsp;""")
        assertEquals("Alice<br>Next", tracks.first().captions[1].html)
        assertEquals("&nbsp;<br>&nbsp;", tracks.first().captions.last().html)
        assertEquals("&nbsp;", tracks.last().captions.last().html)
    }
    @Test fun bomAndDeclaredLegacyEncodingDecodeWithoutReplacingCharacters() {
        val text = "<SYNC Start=0><P>字幕"
        assertEquals(text, SamiSubtitles.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        val korean = "<META charset=EUC-KR><SYNC Start=0><P>안녕"
        assertEquals(korean, SamiSubtitles.decode(korean.toByteArray(java.nio.charset.Charset.forName("EUC-KR"))))
        assertTrue(runCatching { SamiSubtitles.decode(byteArrayOf(0xff.toByte(), 0x00)) }.isFailure)
    }
    @Test fun duplicateSyncKeepsFinalStateAndBadTimesCannotBeAttached() {
        val values = SamiSubtitles.parse("<sync start=0><p>first<sync start=0><p>last<sync start=500><p>&nbsp;").single().captions
        assertEquals("last", values.first().html); assertEquals(500L, values.first().durationMs)
        for (text in listOf("<sync><p>bad", "<sync start=-1><p>bad", "<sync start=1><p>ok<sync start=0><p>bad", "<sync start=9999999999999999999><p>bad"))
            assertTrue(runCatching { SamiSubtitles.parse(text) }.isFailure)
    }
}
