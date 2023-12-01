package com.leagueofrestaurant.api.kakao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional

public class KakaoServiceTest {
    @Autowired
    KakaoService kakaoService;

    @Test
    public void 테스트() throws JsonProcessingException {
        String a = kakaoService.fetchKakaoSearch("일미닭갈비파전");
        String b = kakaoService.selectStore(a);
        System.out.println(b);
    }

}