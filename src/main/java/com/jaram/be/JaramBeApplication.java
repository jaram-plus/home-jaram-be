package com.jaram.be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // MemberLifecycleService.sweepToday
public class JaramBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(JaramBeApplication.class, args);
	}

}
