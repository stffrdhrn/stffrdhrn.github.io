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
