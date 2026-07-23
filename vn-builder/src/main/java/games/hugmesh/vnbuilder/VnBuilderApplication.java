package games.hugmesh.vnbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VnBuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(VnBuilderApplication.class, args);
    }
}
