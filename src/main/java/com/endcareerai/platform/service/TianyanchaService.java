package com.endcareerai.platform.service;

import com.endcareerai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 天眼查企业三要素验证服务
 * 调用天眼查开放平台"企业三要素验证"接口，校验企业名称、统一社会信用代码和法人代表是否一致
 * API 文档参考: https://open.tianyancha.com
 */
@Slf4j
@Service
public class TianyanchaService {

    @Value("${tianyancha.api-key:}")
    private String apiKey;

    @Value("${tianyancha.base-url:https://open.api.tianyancha.com}")
    private String baseUrl;

    @Value("${tianyancha.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 企业三要素验证接口路径
     */
    private static final String VERIFY_PATH = "/services/open/ic/verify/2.0";

    public TianyanchaService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    /**
     * 企业三要素验证
     * 调用天眼查接口验证企业名称、统一社会信用代码和法人代表是否匹配
     *
     * @param companyName         企业名称
     * @param creditCode          统一社会信用代码
     * @param legalRepresentative 法人代表姓名
     * @throws BusinessException 当验证失败或企业信息不匹配时抛出
     */
    public void verifyEnterprise(String companyName, String creditCode, String legalRepresentative) {
        if (!enabled) {
            log.info("天眼查企业验证未启用，跳过验证: companyName={}, creditCode={}", companyName, creditCode);
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("天眼查企业验证已启用但 API Key 未配置，无法进行企业三要素验证");
            throw new BusinessException("企业信息验证服务未正确配置，请联系管理员");
        }

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + VERIFY_PATH)
                    .queryParam("name", companyName)
                    .queryParam("code", creditCode)
                    .queryParam("legalPersonName", legalRepresentative)
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.error("天眼查企业验证请求失败: status={}", response.getStatusCode());
                throw new BusinessException("企业信息验证服务暂不可用，请稍后重试");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String errorCode = root.path("error_code").asText("1");
            String reason = root.path("reason").asText("");

            if (!"0".equals(errorCode)) {
                log.warn("天眼查企业验证返回错误: errorCode={}, reason={}", errorCode, reason);
                throw new BusinessException("企业信息验证失败: " + reason);
            }

            JsonNode result = root.path("result");
            // 验证结果：1=一致，2=不一致，3=查无此企业
            int verifyResult = result.path("verifyResult").asInt(0);
            if (verifyResult != 1) {
                log.warn("天眼查企业三要素验证不通过: verifyResult={}, companyName={}", verifyResult, companyName);
                throw new BusinessException("企业三要素验证不通过，请确认企业名称、信用代码和法人代表信息是否正确");
            }

            log.info("天眼查企业三要素验证通过: companyName={}, creditCode={}", companyName, creditCode);

        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("调用天眼查API异常: {}", e.getMessage(), e);
            throw new BusinessException("企业信息验证服务调用失败，请稍后重试");
        } catch (Exception e) {
            log.error("天眼查企业验证处理异常: {}", e.getMessage(), e);
            throw new BusinessException("企业信息验证服务异常，请稍后重试");
        }
    }
}
