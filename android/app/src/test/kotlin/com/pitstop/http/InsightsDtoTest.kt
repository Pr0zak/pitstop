package com.pitstop.http

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /analytics/insights, shaped like the backend's response (synthetic
 * values), decoded with the app's Json config. Covers every chart kind,
 * ints where floats are usual, and every optional field null or absent.
 */
class InsightsDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    private val body = """
        {"groups":[{"id":"fuel","title":"Fuel economy","subtitle":"What moves your MPG"},
                   {"id":"engine","title":"Engine health","subtitle":"Early warnings"}],
         "attention":["leak","missing_card"],
         "insights":[
          {"id":"temp","group":"fuel","title":"Temperature","status":"info",
           "headline":"Best on warm days.","detail":null,"value":10,"value_unit":"% swing",
           "chart":{"kind":"bars","y_unit":"mpg","bars":[
              {"label":"Cold","value":17.5,"n":33,"highlight":false},
              {"label":"Warm","value":19,"n":99,"highlight":true},
              {"label":"Hot","value":null,"n":0,"highlight":false}],
             "band":{"lo":-5,"hi":5,"label":"Healthy"},"ref":null},
           "how":"Grouped trips by outside temperature.","tip":"Warm up less.","sample":"Based on 100 trips","progress":null},
          {"id":"leak","group":"tires","title":"Slow leak","status":"bad",
           "headline":"One tire is losing air.","value":-4.6,"value_unit":"psi/day",
           "chart":{"kind":"lines","x_type":"time","y_unit":"psi","series":[
              {"name":"FL","points":[[1700000000000,30.4],[1700086400000,30]]},
              {"name":"FR","points":[[1700000000000,28.1],[1700086400000,null]]}],
             "band":null,"ref":{"value":35,"label":"Door placard"}},
           "how":"Least-squares slope.","tip":null,"sample":null,"progress":null},
          {"id":"warm","group":"engine","title":"Warm-up","status":"good",
           "headline":"Warms up in 4 minutes.","value":4.0,"value_unit":"min warm-up",
           "chart":{"kind":"points","x_unit":"°F outside","y_unit":"min to warm","points":[[78,3.8],[56,5],[40.5,null]]},
           "how":"Time to 160 °F.","tip":null,"sample":"30 cold starts","progress":null},
          {"id":"cyl","group":"engine","title":"Cylinder mode","status":"learning",
           "headline":"Collecting readings.","detail":null,"value":null,"value_unit":null,"chart":null,
           "how":"Needs more data.","tip":null,"sample":null,"progress":{"have":2,"need":7,"unit":"days"}},
          {"id":"bare","group":"engine","title":"Bare","status":"something_new","headline":"Minimal card.","how":"x"}
         ],
         "generated_at":"2026-01-02T03:04:05.123456+00:00","window_days":365}
    """.trimIndent()

    @Test fun `every chart kind and null field decodes`() {
        val dto = json.decodeFromString(InsightsResponseDto.serializer(), body)
        assertEquals(2, dto.groups.size)
        assertEquals(listOf("leak", "missing_card"), dto.attention)
        assertEquals(365.0, dto.windowDays)
        assertEquals(5, dto.insights.size)

        val temp = dto.insights[0]
        assertEquals(10.0, temp.value)
        assertNull(temp.detail)
        val bars = temp.chart!!
        assertEquals("bars", bars.kind)
        assertEquals(3, bars.bars!!.size)
        assertEquals(19.0, bars.bars!![1].value)
        assertTrue(bars.bars!![1].highlight)
        assertNull(bars.bars!![2].value)
        assertEquals(-5.0, bars.band!!.lo)
        assertNull(bars.ref)
        assertNull(bars.series)

        val lines = dto.insights[1].chart!!
        assertEquals("time", lines.xType)
        assertEquals(2, lines.series!!.size)
        assertEquals(1_700_000_000_000.0, lines.series!![0].points[0][0])
        assertNull(lines.series!![1].points[1][1])
        assertEquals(35.0, lines.ref!!.value)
        assertNull(lines.band)

        val points = dto.insights[2].chart!!
        assertEquals("°F outside", points.xUnit)
        assertEquals(3, points.points!!.size)
        assertNull(points.points!![2][1])

        val learning = dto.insights[3]
        assertNull(learning.chart)
        assertNull(learning.value)
        assertEquals(2.0, learning.progress!!.have, 0.0)
        assertEquals(7.0, learning.progress!!.need, 0.0)
        assertEquals("days", learning.progress!!.unit)

        val bare = dto.insights[4]
        assertEquals("something_new", bare.status)
        assertNull(bare.chart)
        assertNull(bare.progress)
        assertNull(bare.sample)
        assertFalse(bare.headline.isEmpty())
    }

    @Test fun `empty response decodes`() {
        val dto = json.decodeFromString(InsightsResponseDto.serializer(), """{"groups":[],"attention":[],"insights":[]}""")
        assertTrue(dto.insights.isEmpty())
        assertNull(dto.generatedAt)
    }
}
