package com.example.gnsslogger.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFileParserTest {

    @Test
    fun parseLocationCsvKeepsValidPointsAndSkipsInvalidRows() {
        val csv = """
            timestamp_ms,timestamp_iso,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg,accuracy_m,quality
            1000,2026-05-28T10:00:00,gps,34.10000000,108.90000000,410.5,,,8.0,good
            2000,2026-05-28T10:00:01,gps,0.00000000,0.00000000,410.5,,,8.0,good
            3000,2026-05-28T10:00:02,gps,34.10010000,108.90010000,411.5,,,120.0,poor
            4000,2026-05-28T10:00:03,gps,34.10020000,108.90020000,412.5,,,18.0,fair
        """.trimIndent()

        val track = TrackFileParser.parse("sample_location.csv", csv)

        assertEquals("CSV", track.sourceType)
        assertEquals(2, track.points.size)
        assertEquals(34.1, track.points.first().latitude, 0.0000001)
        assertEquals(108.9002, track.points.last().longitude, 0.0000001)
        assertEquals("fair", track.points.last().quality)
    }

    @Test
    fun parseKmlReadsCoordinateBlocks() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <LineString>
                    <coordinates>
                      108.90000000,34.10000000,410.5
                      108.90100000,34.10100000,411.5
                    </coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val track = TrackFileParser.parse("sample_track.kml", kml)

        assertEquals("KML", track.sourceType)
        assertEquals(2, track.points.size)
        assertEquals(108.901, track.points.last().longitude, 0.0000001)
        assertTrue(track.points.all { it.latitude > 0.0 && it.longitude > 0.0 })
    }
}
