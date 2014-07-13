---
layout: default
status: publish
published: true
title: ServiceUtil.java
author:
  display_name: shorne
  login: admin
  email: shorne@gmail.com
  url: http://blog.shorne-pla.net
author_login: admin
author_email: shorne@gmail.com
author_url: http://blog.shorne-pla.net
wordpress_id: 115
wordpress_url: http://blog.shorne-pla.net/?page_id=115
date: !binary |-
  MjAwOC0xMi0xNiAyMDoyOTowNyArMDkwMA==
date_gmt: !binary |-
  MjAwOC0xMi0xNiAxMzoyOTowNyArMDkwMA==
categories:
- Uncategorized
tags: []
comments: []
---
<a href="/content/2008/12/serviceutil.java">download</a>
{% highlight java %}
package net.shornepla.gsx.util;
import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gdata.client.docs.DocsService;
import com.google.gdata.client.spreadsheet.SpreadsheetService;
import com.google.gdata.util.AuthenticationException;
import com.google.gdata.util.common.base.StringUtil;
public class ServiceUtil {
    private static final Logger logger = LoggerFactory.getLogger(ServiceUtil.class);
    private static Properties config;
    static {
        config = new Properties();
        try {
            config.load(ClassLoader.getSystemResourceAsStream("credentials.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static DocsService getDocsService(String app) {
        DocsService service = new DocsService(app);
        try {
            service.setUserCredentials(getUsername(),  getPassword());
        } catch (AuthenticationException e) {
            logger.error("Failed to set uesr credentials", e);
        }
        return service;
    }
    public static SpreadsheetService getSpreadsheetService(String app) {
        SpreadsheetService service = new SpreadsheetService(app);
        try {
            service.setUserCredentials(getUsername(),  getPassword());
        } catch (AuthenticationException e) {
            logger.error("Failed to set uesr credentials", e);
        }
        return service;
    }
    public static URL getWorkSheetEntryURL() {
        URL wseUrl = null;
        try {
            String spreadsheet = getSpreadsheet();
            String worksheet = getWorksheet();
            if (StringUtil.isEmptyOrWhitespace(spreadsheet)) {
                throw new Exception("spreadsheet is preperty must be set in credentials.properties");
            }
            if (StringUtil.isEmptyOrWhitespace(worksheet)) {
                throw new Exception("worksheet is preperty must be set in credentials.properties");
            }
            wseUrl = new URL("http://spreadsheets.google.com/feeds/worksheets/"+spreadsheet+"/private/full/"+worksheet);
        } catch (Exception e) {
            logger.error("Failed to build worksheet entry URL", e);
        }
        return wseUrl;
    }
    private static String getUsername() {
        return config.getProperty("username");
    }
    private static String getPassword() {
        return config.getProperty("password");
    }
    private static String getSpreadsheet() {
        return config.getProperty("spreadsheet");
    }
    private static String getWorksheet() {
        return config.getProperty("worksheet");
    }
}
{% endhighlight %}
