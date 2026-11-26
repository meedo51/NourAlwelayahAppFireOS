package com.nouralwelayah.clock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchRepositoryTest {

    @Test
    fun `parseSearchResponseJson parses Arabic and English videoRenderer items with durations and hqdefault thumbnails`() {
        val sampleResponse = """
        {
          "contents": {
            "twoColumnSearchResultsRenderer": {
              "primaryContents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "itemSectionRenderer": {
                        "contents": [
                          {
                            "videoRenderer": {
                              "videoId": "dQw4w9WgXcQ",
                              "title": {
                                "runs": [
                                  { "text": "دعاء كميل كامل - بصوت خاشع" }
                                ]
                              },
                              "ownerText": {
                                "runs": [
                                  { "text": "قناة نور الولاية" }
                                ]
                              },
                              "lengthText": {
                                "simpleText": "28:45"
                              }
                            }
                          },
                          {
                            "videoRenderer": {
                              "videoId": "9bZkp7q19f0",
                              "title": {
                                "simpleText": "Surah Yasin Full Recitation - Abdul Basit"
                              },
                              "longBylineText": {
                                "runs": [
                                  { "text": "Holy Quran Channel" }
                                ]
                              },
                              "lengthText": {
                                "runs": [
                                  { "text": "1:04:12" }
                                ]
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()

        val items = YouTubeSearchRepository.parseSearchResponseJson(sampleResponse)
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("dQw4w9WgXcQ", first.videoId)
        assertEquals("دعاء كميل كامل - بصوت خاشع", first.title)
        assertEquals("قناة نور الولاية", first.channelName)
        assertEquals("28:45", first.durationText)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", first.thumbnailUrl)

        val second = items[1]
        assertEquals("9bZkp7q19f0", second.videoId)
        assertEquals("Surah Yasin Full Recitation - Abdul Basit", second.title)
        assertEquals("Holy Quran Channel", second.channelName)
        assertEquals("1:04:12", second.durationText)
        assertEquals("https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg", second.thumbnailUrl)
    }

    @Test
    fun `parseSearchResponseJson filters out ads live streams without duration and invalid videoIds`() {
        val mixedResponse = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "adSlotRenderer": {
                    "videoRenderer": {
                      "videoId": "AdVideo1234",
                      "title": { "simpleText": "Sponsored Ad Video" },
                      "lengthText": { "simpleText": "0:30" }
                    }
                  }
                },
                {
                  "videoRenderer": {
                    "videoId": "LiveStrm123",
                    "title": { "runs": [{ "text": "Live Stream Without Duration" }] },
                    "ownerText": { "runs": [{ "text": "Live Channel" }] }
                  }
                },
                {
                  "videoRenderer": {
                    "videoId": "short_id",
                    "title": { "simpleText": "Invalid ID Length" },
                    "lengthText": { "simpleText": "3:15" }
                  }
                },
                {
                  "videoRenderer": {
                    "videoId": "BadgeAd1234",
                    "title": { "simpleText": "Ad Badge Video" },
                    "lengthText": { "simpleText": "1:00" },
                    "badges": [
                      {
                        "metadataBadgeRenderer": {
                          "style": "BADGE_STYLE_TYPE_AD",
                          "label": "Ad"
                        }
                      }
                    ]
                  }
                },
                {
                  "videoRenderer": {
                    "videoId": "ValidVid123",
                    "title": { "runs": [{ "text": "زيارة عاشوراء كاملة" }] },
                    "ownerText": { "runs": [{ "text": "Nour Alwelayah Media" }] },
                    "lengthText": { "simpleText": "19:20" }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val items = YouTubeSearchRepository.parseSearchResponseJson(mixedResponse)
        assertEquals(1, items.size)
        assertEquals("ValidVid123", items[0].videoId)
        assertEquals("زيارة عاشوراء كاملة", items[0].title)
        assertEquals("Nour Alwelayah Media", items[0].channelName)
        assertEquals("19:20", items[0].durationText)
        assertEquals("https://i.ytimg.com/vi/ValidVid123/hqdefault.jpg", items[0].thumbnailUrl)
    }

    @Test
    fun `parseSearchResponseJson handles empty and malformed JSON responses gracefully`() {
        assertTrue(YouTubeSearchRepository.parseSearchResponseJson("").isEmpty())
        assertTrue(YouTubeSearchRepository.parseSearchResponseJson("   ").isEmpty())
        assertTrue(YouTubeSearchRepository.parseSearchResponseJson("{}").isEmpty())
        assertTrue(YouTubeSearchRepository.parseSearchResponseJson("not-json-at-all").isEmpty())
        assertTrue(YouTubeSearchRepository.parseSearchResponseJson("{\"contents\": [broken").isEmpty())
    }

    @Test
    fun `buildSearchRequestPayload produces valid Innertube WEB context for ar and en`() {
        val arPayload = YouTubeSearchRepository.buildSearchRequestPayload("دعاء الصباح", "ar")
        assertTrue(arPayload.contains("\"clientName\":\"WEB\""))
        assertTrue(arPayload.contains("\"clientVersion\":\"2.20260101.00.00\""))
        assertTrue(arPayload.contains("\"hl\":\"ar\""))
        assertTrue(arPayload.contains("\"query\":\"دعاء الصباح\""))

        val enPayload = YouTubeSearchRepository.buildSearchRequestPayload("Dua Kumayl", "en")
        assertTrue(enPayload.contains("\"hl\":\"en\""))
        assertTrue(enPayload.contains("\"query\":\"Dua Kumayl\""))
    }
}
