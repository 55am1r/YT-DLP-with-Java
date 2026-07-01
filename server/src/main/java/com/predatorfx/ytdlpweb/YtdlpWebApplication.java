package com.predatorfx.ytdlpweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YtdlpWebApplication {

	public static void main(String[] args) {
		SpringApplication.run(YtdlpWebApplication.class, args);
	}

}
