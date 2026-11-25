package com.example

import com.example.data.remote.XPollParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XPollParserTest {

    @Test
    fun `parses X GraphQL card binding_values array payload`() {
        val rawGraphQlCard = """
            {
              "rest_id": "card://1900112233",
              "legacy": {
                "name": "poll3choice_text_only",
                "url": "card://1900112233",
                "binding_values": [
                  { "key": "choice1_label", "value": { "string_value": "Jetpack Compose" } },
                  { "key": "choice1_count", "value": { "string_value": "420" } },
                  { "key": "choice2_label", "value": { "string_value": "XML Views" } },
                  { "key": "choice2_count", "value": { "string_value": "80" } },
                  { "key": "choice3_label", "value": { "string_value": "Both" } },
                  { "key": "choice3_count", "value": { "string_value": "100" } },
                  { "key": "counts_are_final", "value": { "boolean_value": false } },
                  { "key": "end_datetime_utc", "value": { "string_value": "2030-05-01T18:00:00Z" } }
                ]
              }
            }
        """.trimIndent()

        val poll = XPollParser.parse(rawGraphQlCard)
        assertNotNull(poll)
        requireNotNull(poll)
        assertEquals("card://1900112233", poll.cardUri)
        assertEquals("poll3choice_text_only", poll.cardName)
        assertEquals(3, poll.choices.size)
        assertEquals(600L, poll.totalVotes)
        assertEquals(70, poll.percentageFor(poll.choices[0]))
        assertFalse(poll.isFinal)
        assertNull(poll.votedChoiceIndex)
        assertTrue(poll.endsAtEpochMs > 0L)
    }

    @Test
    fun `merges X CAPI vote response map without re-fetching tweet`() {
        val initialRaw = """
            {
              "rest_id": "card://555",
              "legacy": {
                "name": "poll2choice_text_only",
                "binding_values": [
                  { "key": "choice1_label", "value": { "string_value": "Kotlin" } },
                  { "key": "choice1_count", "value": { "string_value": "10" } },
                  { "key": "choice2_label", "value": { "string_value": "Java" } },
                  { "key": "choice2_count", "value": { "string_value": "10" } },
                  { "key": "counts_are_final", "value": { "boolean_value": false } }
                ]
              }
            }
        """.trimIndent()

        val initial = requireNotNull(XPollParser.parse(initialRaw))
        val optimistic = initial.withLocalVote(1)
        assertEquals(1, optimistic.votedChoiceIndex)
        assertEquals(11L, optimistic.choices[0].votes)
        assertEquals(21L, optimistic.totalVotes)
        // Duplicate vote attempt is ignored
        assertEquals(optimistic, optimistic.withLocalVote(2))

        val capiResponse = """
            {
              "card": {
                "name": "poll2choice_text_only",
                "binding_values": {
                  "choice1_label": { "string_value": "Kotlin" },
                  "choice1_count": { "string_value": "15" },
                  "choice2_label": { "string_value": "Java" },
                  "choice2_count": { "string_value": "10" },
                  "selected_choice": { "string_value": "1" },
                  "counts_are_final": { "boolean_value": false }
                }
              }
            }
        """.trimIndent()

        val merged = XPollParser.mergeVoteResponse(initial, capiResponse, votedChoiceIndex = 1)
        assertEquals(1, merged.votedChoiceIndex)
        assertEquals(15L, merged.choices[0].votes)
        assertEquals(10L, merged.choices[1].votes)
        assertEquals(25L, merged.totalVotes)
        assertEquals(60, merged.percentageFor(merged.choices[0]))
        assertEquals(40, merged.percentageFor(merged.choices[1]))
    }

    @Test
    fun `parses FxTwitter public open and closed poll payloads`() {
        val openPublicPoll = """
            {
              "choices": [
                { "label": "Dark Mode", "count": 900 },
                { "label": "Light Mode", "count": 100 }
              ],
              "total_votes": 1000,
              "ends_at": "2030-08-15T12:00:00Z",
              "time_left_en": "12 hours left"
            }
        """.trimIndent()

        val parsedOpen = requireNotNull(XPollParser.parse(openPublicPoll))
        assertEquals(2, parsedOpen.choices.size)
        assertEquals(1000L, parsedOpen.totalVotes)
        assertFalse(parsedOpen.isFinal)
        assertEquals(90, parsedOpen.percentageFor(parsedOpen.choices[0]))

        val closedPublicPoll = """
            {
              "options": [
                { "position": 1, "label": "Ship it", "votes": 50 },
                { "position": 2, "label": "Wait", "votes": 50 }
              ],
              "voting_status": "closed"
            }
        """.trimIndent()

        val parsedClosed = requireNotNull(XPollParser.parse(closedPublicPoll))
        assertTrue(parsedClosed.isFinal)
        assertEquals(100L, parsedClosed.totalVotes)
        assertEquals(50, parsedClosed.percentageFor(parsedClosed.choices[0]))
    }
}
