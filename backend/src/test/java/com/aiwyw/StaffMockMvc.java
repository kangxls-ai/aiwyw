package com.aiwyw;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** MVC业务用例模拟可信网关；真实路径、隔离Cookie和访问失败由独立HTTP用例验证。 */
@TestConfiguration(proxyBeanMethods = false)
class StaffMockMvc {
  @Bean
  MockMvcBuilderCustomizer staffRequests(StaffEntry entry) {
    return builder ->
        builder.defaultRequest(
            get("/")
                .with(
                    request -> {
                      if (!StaffEntry.mandatory(request.getRequestURI())
                          || request.getHeader(StaffEntry.HEADER) != null) return request;
                      request.addHeader(StaffEntry.HEADER, entry.path().substring(1));
                      if (request.getCookies() != null
                          && Arrays.stream(request.getCookies())
                              .noneMatch(c -> c.getName().equals(StaffEntry.COOKIE))) {
                        var cookies = new ArrayList<>(Arrays.asList(request.getCookies()));
                        cookies.stream()
                            .filter(c -> c.getName().equals("AIWYW_SESSION"))
                            .findFirst()
                            .ifPresent(
                                c -> cookies.add(new Cookie(StaffEntry.COOKIE, c.getValue())));
                        request.setCookies(cookies.toArray(Cookie[]::new));
                      }
                      return request;
                    }));
  }
}
