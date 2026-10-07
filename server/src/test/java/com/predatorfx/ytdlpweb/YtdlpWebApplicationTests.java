package com.predatorfx.ytdlpweb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// The app deletes everything in its work directory on startup (orphaned job folders
// can't be recovered after a restart). With the real config that is the LIVE server's
// downloads folder, so a plain `gradlew test` on the host that serves the team wiped
// whatever its users were mid-way through downloading. Point the test at a throwaway one.
// Same for the admin records: the live server keeps writing them, and a test context
// saving its own copy over them would lose the team's history. No brew, no geo lookups.
@SpringBootTest(properties = {
		"ytdlp.work-dir=${java.io.tmpdir}/ytdlp-web-test-work",
		"app.admin.data-dir=${java.io.tmpdir}/ytdlp-admin-test-${random.uuid}",
		"app.geo.enabled=false",
		"brew.bin=/usr/bin/true"
})
class YtdlpWebApplicationTests {

	@Test
	void contextLoads() {
	}
}
