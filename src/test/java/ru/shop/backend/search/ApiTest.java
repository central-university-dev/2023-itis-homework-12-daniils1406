package ru.shop.backend.search;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import ru.shop.backend.search.dto.SearchResult;

import java.io.File;

import static java.lang.Thread.sleep;

@RunWith(SpringJUnit4ClassRunner.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ApiTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @BeforeClass
    public static void init() throws InterruptedException {
        containers.start();
        System.out.println();
        sleep(150000);//по какой то причине контейнеры поднимаются долго, почти 2,5 минуты
    }

    @Container
    public static DockerComposeContainer containers =
            new DockerComposeContainer(new File("src/test/resources/docker-compose-test.yml"));


    @Test
    public void searchByEmptyText(){//по сути просто проверил что приложение запускается
        SearchResult response=testRestTemplate.getForEntity("/api/search", SearchResult.class).getBody();
        SearchResult searchResult=new SearchResult(null,null,null);
        Assert.assertEquals(response.getItems(),searchResult.getItems());
        Assert.assertEquals(response.getCategories(),searchResult.getCategories());
        Assert.assertEquals(response.getTypeQueries(),searchResult.getTypeQueries());
    }
}
