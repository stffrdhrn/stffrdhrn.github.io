---
layout: page
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
<p><a href="/content/2008/12/serviceutil.java">download</a><br />
[code lang="java"]<br />
package net.shornepla.gsx.util;</p>
<p>import java.io.IOException;<br />
import java.net.URL;<br />
import java.util.Properties;</p>
<p>import org.slf4j.Logger;<br />
import org.slf4j.LoggerFactory;</p>
<p>import com.google.gdata.client.docs.DocsService;<br />
import com.google.gdata.client.spreadsheet.SpreadsheetService;<br />
import com.google.gdata.util.AuthenticationException;<br />
import com.google.gdata.util.common.base.StringUtil;</p>
<p>public class ServiceUtil {</p>
<p>    private static final Logger logger = LoggerFactory.getLogger(ServiceUtil.class);<br />
    private static Properties config;</p>
<p>    static {<br />
        config = new Properties();<br />
        try {<br />
            config.load(ClassLoader.getSystemResourceAsStream("credentials.properties"));<br />
        } catch (IOException e) {<br />
            e.printStackTrace();<br />
        }<br />
    }</p>
<p>    public static DocsService getDocsService(String app) {</p>
<p>        DocsService service = new DocsService(app);<br />
        try {<br />
            service.setUserCredentials(getUsername(),  getPassword());<br />
        } catch (AuthenticationException e) {<br />
            logger.error("Failed to set uesr credentials", e);<br />
        }</p>
<p>        return service;<br />
    }</p>
<p>    public static SpreadsheetService getSpreadsheetService(String app) {</p>
<p>        SpreadsheetService service = new SpreadsheetService(app);<br />
        try {<br />
            service.setUserCredentials(getUsername(),  getPassword());<br />
        } catch (AuthenticationException e) {<br />
            logger.error("Failed to set uesr credentials", e);<br />
        }</p>
<p>        return service;<br />
    }</p>
<p>    public static URL getWorkSheetEntryURL() {</p>
<p>        URL wseUrl = null;<br />
        try {<br />
            String spreadsheet = getSpreadsheet();<br />
            String worksheet = getWorksheet();</p>
<p>            if (StringUtil.isEmptyOrWhitespace(spreadsheet)) {<br />
                throw new Exception("spreadsheet is preperty must be set in credentials.properties");<br />
            }</p>
<p>            if (StringUtil.isEmptyOrWhitespace(worksheet)) {<br />
                throw new Exception("worksheet is preperty must be set in credentials.properties");<br />
            }<br />
            wseUrl = new URL("http://spreadsheets.google.com/feeds/worksheets/"+spreadsheet+"/private/full/"+worksheet);</p>
<p>        } catch (Exception e) {<br />
            logger.error("Failed to build worksheet entry URL", e);<br />
        }<br />
        return wseUrl;<br />
    }</p>
<p>    private static String getUsername() {<br />
        return config.getProperty("username");<br />
    }</p>
<p>    private static String getPassword() {<br />
        return config.getProperty("password");<br />
    }</p>
<p>    private static String getSpreadsheet() {<br />
        return config.getProperty("spreadsheet");<br />
    }</p>
<p>    private static String getWorksheet() {<br />
        return config.getProperty("worksheet");<br />
    }<br />
}<br />
[/code]</p>
