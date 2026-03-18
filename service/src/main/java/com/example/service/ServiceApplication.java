package com.example.service;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authorization.EnableMultiFactorAuthentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ott.OneTimeTokenLoginConfigurer;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@EnableMultiFactorAuthentication( authorities =  {FactorGrantedAuthority.OTT_AUTHORITY, FactorGrantedAuthority.PASSWORD_AUTHORITY})
//@Import(MyBeanRegistrar.class)
@EnableResilientMethods
@ImportHttpServices(CatFactsClient.class)
@SpringBootApplication
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }

    @Bean
    JdbcPostgresDialect jdbcPostgresDialect() {
        return JdbcPostgresDialect.INSTANCE;
    }

    @Bean
    JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

    @Bean
    Customizer<HttpSecurity> httpSecurityCustomizer() {
        return http ->
                http.oneTimeTokenLogin(httpSecurityOneTimeTokenLoginConfigurer -> httpSecurityOneTimeTokenLoginConfigurer
                        .tokenGenerationSuccessHandler((request, response, oneTimeToken) -> {
                            response.getWriter().println("you've got console mail!");
                            response.setContentType(MediaType.TEXT_PLAIN_VALUE);

                            IO.println("please go to http://localhost:8080/login/ott?token=" +
                                    oneTimeToken.getTokenValue());
                        }));
    }

}

@Controller
@ResponseBody
class MeController {

    @GetMapping("/")
    Map<String, Object> index(Principal principal) {
        return Map.of("name", principal.getName()) ;
    }
}

/*
class MyBeanRegistrar implements BeanRegistrar {

    // sr. tony hoare

    @Override
    public void register(@NonNull BeanRegistry registry, @NonNull Environment env) {
        registry.registerBean(JdbcPostgresDialect.class , spec -> spec
                .supplier(supplierContext -> JdbcPostgresDialect.INSTANCE));
    }
}*/


@Controller
@ResponseBody
class CatsFactsClientController {

    private final CatFactsClient factsClient;


    CatsFactsClientController(CatFactsClient factsClient) {
        this.factsClient = factsClient;
    }

    private final AtomicInteger counter = new AtomicInteger(0);

    @GetMapping("/cats")
    @Retryable(maxRetries = 5, includes = IllegalStateException.class)
    @ConcurrencyLimit(10)
    CatFacts facts() {

        if (this.counter.getAndIncrement() < 5) {
            IO.println("oops!");
            throw new IllegalStateException();
        }

        IO.println("facts!");
        return this.factsClient.facts();
    }
}

record CatFact(String fact) {
}

record CatFacts(Collection<CatFact> facts) {
}

interface CatFactsClient {
    @GetExchange("https://www.catfacts.net/api")
    CatFacts facts();
}

/*
@Component
class CatFactsClient {

    private final RestClient http;

    CatFactsClient(RestClient.Builder http) {
        this.http = http.build();
    }

    CatFacts facts() {
        return this.http.get()
                .uri("https://www.catfacts.net/api")
                .retrieve()
                .body(CatFacts.class);
    }
}*/

interface DogRepository extends ListCrudRepository<Dog, Integer> {

    // select * from dogs where name = ?
    Collection<Dog> findByName(String name);
}

// look mom, no lombok!
record Dog(@Id int id, String name, String description, String owner) {
}

@Controller
@ResponseBody
class DogsController {

    private final DogRepository repository;

    DogsController(DogRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/dogs", version = "1.1")
    Collection<Dog> all() {
        return repository.findAll();
    }

    @GetMapping(value = "/dogs", version = "1.0")
    Collection<Map<String, Object>> findAll() {
        return this.repository
                .findAll()
                .stream()
                .map(dog -> Map.of("id", (Object) dog.id(), "fullName", dog.name()))
                .toList();
    }
}

@Controller
@ResponseBody
class DogsAdoptionController {

    private final DogRepository dogRepository;

    DogsAdoptionController(DogRepository dogRepository) {
        this.dogRepository = dogRepository;
    }

    @PostMapping("/dogs/{dogId}/adoptions")
    void adopt(
            @PathVariable int dogId,
            @RequestParam String owner) {


        this.dogRepository.findById(dogId).ifPresent(dog -> {
            var updated = this.dogRepository.save(
                    new Dog(dog.id(), dog.name(), dog.description(), owner));
            IO.println("adopted " + updated);

        });
    }

}