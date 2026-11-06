package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.web.context.request.*;

/** 从可信代理链右侧寻找真实来源，不把客户端任意填写的转发头直接当作IP。禁止DNS解析主机名。 */
final class RequestIp {
  static String numeric(String text) {
    if (text == null
        || text.length() > 45
        || !text.matches("[0-9a-fA-F:.]+")
        || (!text.contains(":") && !text.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) return null;
    try {
      return InetAddress.getByName(text).getHostAddress();
    } catch (Exception e) {
      return null;
    }
  }

  static String resolve(HttpServletRequest req, Settings settings) {
    String peer = numeric(req.getRemoteAddr());
    if (peer == null) return null;
    Set<String> trusted = new HashSet<>();
    for (String value : settings.value("security.trusted_proxies").split(",")) {
      String ip = numeric(value.trim());
      if (ip != null) trusted.add(ip);
    }
    if (!trusted.contains(peer)) return peer;
    String edge = numeric(req.getHeader("X-AIWYW-Peer-IP"));
    if (edge != null) {
      Set<String> front = new HashSet<>();
      for (String value : settings.value("security.trusted_front_proxies").split(",")) {
        String ip = numeric(value.trim());
        if (ip != null) front.add(ip);
      }
      if (!front.contains(edge)) return edge;
      trusted.addAll(front);
    }
    String header = req.getHeader("X-Forwarded-For");
    if (header == null || header.length() > 1000) return peer;
    String[] chain = header.split(",", -1);
    if (chain.length > 20) return peer;
    for (int i = chain.length - 1; i >= 0; i--) {
      String ip = numeric(chain[i].trim());
      if (ip == null) return peer;
      if (!trusted.contains(ip)) return ip;
    }
    return peer;
  }

  static String current() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
      return (String) attrs.getRequest().getAttribute("aiwyw.source-ip");
    return null;
  }
}
