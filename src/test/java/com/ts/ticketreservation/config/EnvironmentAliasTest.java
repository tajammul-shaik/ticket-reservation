package com.ts.ticketreservation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Render service sets DB_POOL_SIZE and ADMISSION_ACQUIRE_TIMEOUT. Both names must reach
 * the pool and the admission filter; before the aliases they were silently ignored and the live
 * pool ran at the default of 10.
 */
class EnvironmentAliasTest {

    @Test
    void renderNamesConfigureThePoolAndAdmissionWait() throws IOException {
        var env = environment(Map.of("DB_POOL_SIZE", "5", "ADMISSION_ACQUIRE_TIMEOUT", "3000"));

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("5");
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("5");
        assertThat(admission(env).maxWait()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void documentedNamesWinOverTheAliases() throws IOException {
        var env = environment(Map.of(
                "DB_POOL_SIZE", "5", "DB_POOL_MAX_SIZE", "8",
                "ADMISSION_ACQUIRE_TIMEOUT", "3000", "ADMISSION_MAX_WAIT", "2s"));

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("8");
        assertThat(admission(env).maxWait()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void defaultsApplyWhenNeitherNameIsSet() throws IOException {
        var env = environment(Map.of());

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        assertThat(admission(env).maxWait()).isEqualTo(Duration.ofSeconds(5));
    }

    /** application.yml with application-prod.yml on top, resolved against the given variables. */
    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        var env = new StandardEnvironment();
        var sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var loader = new YamlPropertySourceLoader();
        sources.addFirst(new MapPropertySource("variables", variables));
        loader.load("prod", new ClassPathResource("application-prod.yml")).forEach(sources::addLast);
        loader.load("base", new ClassPathResource("application.yml")).forEach(sources::addLast);
        return env;
    }

    private static AdmissionProperties admission(StandardEnvironment env) {
        return Binder.get(env).bindOrCreate("app.admission", AdmissionProperties.class);
    }
}
