/**
 * Cerberus Copyright (C) 2013 - 2017 cerberustesting
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This file is part of Cerberus.
 *
 * Cerberus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cerberus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cerberus.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.cerberus.service.har.impl;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.cerberus.crud.entity.Invariant;
import org.cerberus.crud.service.IInvariantService;
import org.cerberus.crud.service.IParameterService;
import org.cerberus.exception.CerberusException;
import org.cerberus.service.har.IHarService;
import org.cerberus.service.har.entity.HarStat;
import org.cerberus.util.StringUtil;
import org.cerberus.util.answer.AnswerList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 *
 * @author bcivel
 */
@Service
public class HarService implements IHarService {

    @Autowired
    private IParameterService parameterService;
    @Autowired
    private IInvariantService invariantService;

    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(HarService.class);

    private static final String DATE_FORMAT = "yy-MM-dd'T'HH:mm:ss.S'Z'";
    private static final String PROVIDER_INTERNAL = "internal";
    private static final String PROVIDER_UNKNOWN = "unknown";
    private static final String PROVIDER_THIRDPARTY = "thirdparty";
    private static final String PROVIDER_IGNORE = "ignore";

    @Override
    public JSONObject enrichWithStats(JSONObject har, String domains, String system) {
        LOG.debug("Enriching HAR file with stats.");
        try {
            JSONArray harEntries = har.getJSONObject("log").getJSONArray("entries");

            HashMap<String, HarStat> target = new HashMap<>();
            HarStat harTotalStat = new HarStat();
            HarStat harProviderStat = new HarStat();

            String url = null;
            String provider = null;

            // Load third party from json file.
            HashMap<String, List<String>> providersRules = loadProvidersExternal();
            // Load third party from invariant WEBPERFTHRIDPARTY.
            providersRules = loadProvidersInternal(providersRules);

            List<String> internalRules = new ArrayList<>();
            String[] dList = domains.split(",");
            for (String domain : dList) {
                internalRules.add(domain.trim());
            }

            String ignore = parameterService.getParameterStringByKey("cerberus_webperf_ignoredomainlist", system, "");
            List<String> ignoreRules = new ArrayList<>();
            dList = ignore.split(",");
            for (String domain : dList) {
                ignoreRules.add(domain.trim());
            }

            for (int i = 0; i < harEntries.length(); i++) {

                url = harEntries.getJSONObject(i).getJSONObject("request").getString("url");

                LOG.debug("Process hit " + i + " URL : " + url);
                // Getting provider from the url called.
                provider = getProvider(url, internalRules, ignoreRules, providersRules);

                // If we don't IGNORE, we add it to total.
                if (!provider.equalsIgnoreCase(PROVIDER_IGNORE)) {
                    harTotalStat = processEntry(harTotalStat, harEntries.getJSONObject(i), url);
                }

                // In all cases, we enrish the stat of the HasMap (if it exist) and put it back.
                if (!target.containsKey(provider)) {
                    harProviderStat = new HarStat();
                } else {
                    harProviderStat = target.get(provider);
                }
                harProviderStat = processEntry(harProviderStat, harEntries.getJSONObject(i), url);
                target.put(provider, harProviderStat);

            }

            // Build Recap of the total
            harTotalStat = processRecap(harTotalStat);

            JSONObject stat = new JSONObject();
            JSONObject thirdPartyStat = new JSONObject();
            // Adding total to HAR JSON.
            stat = addStat("total", harTotalStat, stat);
            // Adding all providers to HAR JSON.
            int nbTP = 0;
            for (Map.Entry<String, HarStat> entry : target.entrySet()) {
                String key = entry.getKey();
                HarStat val = entry.getValue();
                // Build Recap of the provider
                val = processRecap(val);

                if (key.equals(PROVIDER_INTERNAL) || key.equals(PROVIDER_UNKNOWN) || key.equals(PROVIDER_IGNORE)) {
                    stat = addStat(key, val, stat);
                } else {
                    nbTP++;
                    thirdPartyStat = addStat(key, val, thirdPartyStat);
                }
            }
            stat.put(PROVIDER_THIRDPARTY, thirdPartyStat);

            // Adding total ThirdParty nb to root level
            stat.put("nbThirdParty", nbTP);

            har.put("stat", stat);
            return har;

        } catch (JSONException ex) {
            LOG.error("Exception when trying to enrich har file.", ex);
        } catch (Exception ex) {
            LOG.error("Exception when trying to enrich har file.", ex);
        }
        return har;
    }

    private HashMap<String, List<String>> loadProvidersExternal() {
        HashMap<String, List<String>> rules = new HashMap<>();
        try {

            String configFile = parameterService.getParameterStringByKey("cerberus_webperf_thirdpartyfilepath", "", "");
            if (StringUtil.isNullOrEmpty(configFile)) {
                LOG.warn("Could not load config file of Web Third Party. Please define a valid parameter for cerberus_webperf_thirdpartyfilepath.");
                return rules;
            }

            if (!Files.exists(Paths.get(configFile))) {
                LOG.error("Could not load config file of Web Third Party. File " + configFile + " does not exist. Please define a valid parameter for cerberus_webperf_thirdpartyfilepath.");
                return rules;
            }

            StringBuilder fileContent = new StringBuilder();
            try (Stream<String> stream = Files.lines(Paths.get(configFile), StandardCharsets.UTF_8)) {
                stream.forEach(s -> fileContent.append(s).append("\n"));
            } catch (Exception e) {
                LOG.error(e, e);
            }
            String thirdPartyList = fileContent.toString();

//            LOG.debug(thirdPartyList);
            JSONArray json = new JSONArray(thirdPartyList);

            for (int i = 0; i < json.length(); i++) {
                List<String> tmpList = new ArrayList<>();
                JSONObject thirdParty = json.getJSONObject(i);
                for (int j = 0; j < thirdParty.getJSONArray("domains").length(); j++) {
                    tmpList.add(thirdParty.getJSONArray("domains").getString(j));
                }
                rules.put(thirdParty.getString("name"), tmpList);
            }

            return rules;

        } catch (JSONException ex) {
            LOG.error("JSON Exception during loading of Third Party config.", ex);
        }
        return rules;
    }

    private HashMap<String, List<String>> loadProvidersInternal(HashMap<String, List<String>> list) {
        try {

            List<Invariant> invList = new ArrayList<>();
            invList = invariantService.readByIdName("WEBPERFTHIRDPARTY");

            for (Invariant invariant : invList) {
                List<String> provInterRules = new ArrayList<>();
                String[] dList = invariant.getGp1().split(",");
                for (String domain : dList) {
                    provInterRules.add(domain.trim());
                }
                list.put(invariant.getValue(), provInterRules);
            }

            return list;

        } catch (CerberusException ex) {
            LOG.error(ex, ex);
        }
        return list;
    }

    private String getProvider(String url, List<String> internalRules, List<String> ingoreRules, HashMap<String, List<String>> providersRules) {
        try {
            URL myURL = new URL(url);

            // We first try from local provider.
            for (String string : internalRules) {
                string = string.replace("*", "");
//                LOG.debug("urlHost : " + myURL.getHost() + " domain : " + string + " URL : " + url);
                if (myURL.getHost().toLowerCase().endsWith(string.toLowerCase())) {
                    return PROVIDER_INTERNAL;
                }
            }

            // We ignore some requests.
            for (String string : ingoreRules) {
                if ((!StringUtil.isNullOrEmpty(string)) && (myURL.getHost().toLowerCase().endsWith(string.toLowerCase()))) {
                    return PROVIDER_IGNORE;
                }
            }

            // We then try from third party provider.
            for (Map.Entry<String, List<String>> entry : providersRules.entrySet()) {
                String key = entry.getKey();
                List<String> val = entry.getValue();
                for (String string : val) {
                    string = string.replace("*", "");
//                    LOG.debug("urlHost : " + myURL.getHost() + " domain : " + string + " URL : " + url);
                    if (myURL.getHost().toLowerCase().endsWith(string.toLowerCase())) {
                        return key;
                    }
                }
            }

            return PROVIDER_UNKNOWN;

        } catch (MalformedURLException ex) {
            Logger.getLogger(HarService.class.getName()).log(Level.SEVERE, null, ex);
        }
        return PROVIDER_UNKNOWN;
    }

    private HarStat processRecap(HarStat harStat) {
        if (harStat.getLastEnd() != null && harStat.getFirstStart() != null) {
            long totDur = harStat.getLastEnd().getTime() - harStat.getFirstStart().getTime();
            harStat.setTimeTotalDuration(Integer.valueOf(String.valueOf(totDur)));
        }
        if (harStat.getNbRequests() != 0) {
            harStat.setTimeAvg(harStat.getTimeSum() / harStat.getNbRequests());
        }
        return harStat;
    }

    private HarStat processEntry(HarStat harStat, JSONObject entry, String url) {

        try {
            String responseType = guessType(entry);
            List<String> tempList;

            int reqSize = entry.getJSONObject("response").getInt("headersSize") + entry.getJSONObject("response").getInt("bodySize");
            int reqTime = entry.getInt("time");
            //2020-02-18T20:53:11.118Z
            String startD = entry.getString("startedDateTime");
            if (startD != null) {
                long endDate = new SimpleDateFormat(DATE_FORMAT).parse(startD).getTime() + reqTime;
                if (harStat.getFirstStartS() == null || startD.compareTo(harStat.getFirstStartS()) < 0) {
                    harStat.setFirstStartS(startD);
                    harStat.setFirstStart(new SimpleDateFormat(DATE_FORMAT).parse(startD));
                    harStat.setFirstEnd(new Date(endDate));
                    harStat.setFirstURL(url);
                    harStat.setFirstDuration(reqTime);
                }
                if (harStat.getLastStartS() == null || harStat.getLastEnd().before(new Date(endDate))) {
                    harStat.setLastStartS(startD);
                    harStat.setLastStart(new SimpleDateFormat(DATE_FORMAT).parse(startD));
                    harStat.setLastEnd(new Date(endDate));
                    harStat.setLastURL(url);
                    harStat.setLastDuration(reqTime);
                }
            }

            switch (responseType) {
                case "js":
                    if (reqSize > 0) {
                        harStat.setJsSizeSum(harStat.getJsSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getJsSizeMax()) {
                        harStat.setJsSizeMax(reqSize);
                        harStat.setUrlJsSizeMax(url);
                    }
                    harStat.setJsHitNb(harStat.getJsHitNb() + 1);
                    tempList = harStat.getJsList();
                    tempList.add(url);
                    harStat.setJsList(tempList);
                    break;
                case "css":
                    if (reqSize > 0) {
                        harStat.setCssSizeSum(harStat.getCssSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getCssSizeMax()) {
                        harStat.setCssSizeMax(reqSize);
                        harStat.setUrlCssSizeMax(url);
                    }
                    harStat.setCssHitNb(harStat.getCssHitNb() + 1);
                    tempList = harStat.getCssList();
                    tempList.add(url);
                    harStat.setCssList(tempList);
                    break;
                case "html":
                    if (reqSize > 0) {
                        harStat.setHtmlSizeSum(harStat.getHtmlSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getHtmlSizeMax()) {
                        harStat.setHtmlSizeMax(reqSize);
                        harStat.setUrlHtmlSizeMax(url);
                    }
                    harStat.setHtmlHitNb(harStat.getHtmlHitNb() + 1);
                    tempList = harStat.getHtmlList();
                    tempList.add(url);
                    harStat.setHtmlList(tempList);
                    break;
                case "img":
                    if (reqSize > 0) {
                        harStat.setImgSizeSum(harStat.getImgSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getImgSizeMax()) {
                        harStat.setImgSizeMax(reqSize);
                        harStat.setUrlImgSizeMax(url);
                    }
                    harStat.setImgHitNb(harStat.getImgHitNb() + 1);
                    tempList = harStat.getImgList();
                    tempList.add(url);
                    harStat.setImgList(tempList);
                    break;
                case "content":
                    if (reqSize > 0) {
                        harStat.setContentSizeSum(harStat.getContentSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getContentSizeMax()) {
                        harStat.setContentSizeMax(reqSize);
                        harStat.setUrlContentSizeMax(url);
                    }
                    harStat.setContentHitNb(harStat.getContentHitNb() + 1);
                    tempList = harStat.getContentList();
                    tempList.add(url);
                    harStat.setContentList(tempList);
                    break;
                case "font":
                    if (reqSize > 0) {
                        harStat.setFontSizeSum(harStat.getFontSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getFontSizeMax()) {
                        harStat.setFontSizeMax(reqSize);
                        harStat.setUrlFontSizeMax(url);
                    }
                    harStat.setFontHitNb(harStat.getFontHitNb() + 1);
                    tempList = harStat.getFontList();
                    tempList.add(url);
                    harStat.setFontList(tempList);
                    break;
                case "other":
                    if (reqSize > 0) {
                        harStat.setOtherSizeSum(harStat.getOtherSizeSum() + reqSize);
                    }
                    if (reqSize > harStat.getOtherSizeMax()) {
                        harStat.setOtherSizeMax(reqSize);
                        harStat.setUrlOtherSizeMax(url);
                    }
                    harStat.setOtherHitNb(harStat.getOtherHitNb() + 1);
                    tempList = harStat.getOtherList();
                    tempList.add(url);
                    harStat.setOtherList(tempList);
                    break;
            }

            int httpS = entry.getJSONObject("response").getInt("status");
            HashMap<Integer, Integer> tmpStat = harStat.getHttpRetCode();

            if (tmpStat.containsKey(httpS)) {
                tmpStat.put(httpS, tmpStat.get(httpS) + 1);
            } else {
                tmpStat.put(httpS, 1);
            }
            harStat.setHttpRetCode(tmpStat);

            harStat.setNbRequests(harStat.getNbRequests() + 1);
            if (reqSize > 0) {
                harStat.setSizeSum(harStat.getSizeSum() + reqSize);
            }
            if (reqSize > 0 && reqSize > harStat.getSizeMax()) {
                harStat.setSizeMax(reqSize);
                harStat.setUrlSizeMax(url);
            }
            harStat.setTimeSum(harStat.getTimeSum() + reqTime);
            if (reqTime > 0 && reqTime > harStat.getTimeMax()) {
                harStat.setTimeMax(reqTime);
                harStat.setUrlTimeMax(url);
            }
            return harStat;

        } catch (JSONException ex) {
            LOG.error("Exception when trying to process entry and enrich HarStat.", ex);
        } catch (Exception ex) {
            LOG.error("Exception when trying to process entry and enrich HarStat.", ex);
            LOG.error(ex, ex);
        }
        return harStat;
    }

    /**
     * Transform the HarStat Object to a JSONObject and add it to stat Object
     * under statKey value.
     *
     * @param har
     * @param domains
     * @param system
     * @return
     */
    private JSONObject addStat(String statKey, HarStat harStat, JSONObject stat) {

        try {
            JSONObject total = new JSONObject();

            JSONObject type = new JSONObject();

            JSONObject js = new JSONObject();
            js.put("sizeSum", harStat.getJsSizeSum());
            js.put("sizeMax", harStat.getJsSizeMax());
            js.put("nbHits", harStat.getJsHitNb());
            js.put("urlMax", harStat.getUrlJsSizeMax());
            js.put("url", harStat.getJsList());
            type.put("js", js);

            JSONObject css = new JSONObject();
            css.put("sizeSum", harStat.getCssSizeSum());
            css.put("sizeMax", harStat.getCssSizeMax());
            css.put("nbHits", harStat.getCssHitNb());
            css.put("urlMax", harStat.getUrlCssSizeMax());
            css.put("url", harStat.getCssList());
            type.put("css", css);

            JSONObject html = new JSONObject();
            html.put("sizeSum", harStat.getHtmlSizeSum());
            html.put("sizeMax", harStat.getHtmlSizeMax());
            html.put("nbHits", harStat.getHtmlHitNb());
            html.put("urlMax", harStat.getUrlHtmlSizeMax());
            html.put("url", harStat.getHtmlList());
            type.put("html", html);

            JSONObject img = new JSONObject();
            img.put("sizeSum", harStat.getImgSizeSum());
            img.put("sizeMax", harStat.getImgSizeMax());
            img.put("nbHits", harStat.getImgHitNb());
            img.put("urlMax", harStat.getUrlImgSizeMax());
            img.put("url", harStat.getImgList());
            type.put("img", img);

            JSONObject other = new JSONObject();
            other.put("sizeSum", harStat.getOtherSizeSum());
            other.put("sizeMax", harStat.getOtherSizeMax());
            other.put("nbHits", harStat.getOtherHitNb());
            other.put("urlMax", harStat.getUrlOtherSizeMax());
            other.put("url", harStat.getOtherList());
            type.put("other", other);

            JSONObject content = new JSONObject();
            content.put("sizeSum", harStat.getContentSizeSum());
            content.put("sizeMax", harStat.getContentSizeMax());
            content.put("nbHits", harStat.getContentHitNb());
            content.put("urlMax", harStat.getUrlContentSizeMax());
            content.put("url", harStat.getContentList());
            type.put("content", content);

            JSONObject font = new JSONObject();
            font.put("sizeSum", harStat.getFontSizeSum());
            font.put("sizeMax", harStat.getFontSizeMax());
            font.put("nbHits", harStat.getFontHitNb());
            font.put("urlMax", harStat.getUrlFontSizeMax());
            font.put("url", harStat.getFontList());
            type.put("font", font);

            total.put("type", type);

            int nb1XX = 0;
            int nb2XX = 0;
            int nb3XX = 0;
            int nb4XX = 0;
            int nb5XX = 0;

            JSONObject httpReqA = new JSONObject();
            HashMap<Integer, Integer> httpStatList = harStat.getHttpRetCode();
            for (Map.Entry<Integer, Integer> entry : httpStatList.entrySet()) {
                Integer key = entry.getKey();
                Integer val = entry.getValue();
                httpReqA.put("nb" + key, val);
                if (key < 200) {
                    nb1XX += val;
                } else if (key < 300) {
                    nb2XX += val;
                } else if (key < 400) {
                    nb3XX += val;
                } else if (key < 500) {
                    nb4XX += val;
                } else {
                    nb5XX += val;
                }
            }
            httpReqA.put("nbALL", harStat.getNbRequests());
            httpReqA.put("nbError", harStat.getNbError());
            httpReqA.put("urlError", harStat.getUrlError());
            httpReqA.put("nb1XX", nb1XX);
            httpReqA.put("nb2XX", nb2XX);
            httpReqA.put("nb3XX", nb3XX);
            httpReqA.put("nb4XX", nb4XX);
            httpReqA.put("nb5XX", nb5XX);
            total.put("httpReq", httpReqA);

            JSONObject size = new JSONObject();
            size.put("sum", harStat.getSizeSum());
            size.put("max", harStat.getSizeMax());
            size.put("urlMax", harStat.getUrlSizeMax());
            total.put("size", size);

            JSONObject time = new JSONObject();
            time.put("sum", harStat.getTimeSum());
            time.put("max", harStat.getTimeMax());
            time.put("avg", harStat.getTimeAvg());
            time.put("urlMax", harStat.getUrlTimeMax());
            time.put("firstStart", harStat.getFirstStartS());
            if (harStat.getFirstEnd() != null) {
                time.put("firstEnd", new SimpleDateFormat(DATE_FORMAT).format(harStat.getFirstEnd()));
            }
            time.put("firstDuration", harStat.getFirstDuration());
            time.put("firstURL", harStat.getFirstURL());
            time.put("lastStart", harStat.getLastStartS());
            if (harStat.getLastEnd() != null) {
                time.put("lastEnd", new SimpleDateFormat(DATE_FORMAT).format(harStat.getLastEnd()));
            }
            time.put("lastDuration", harStat.getLastDuration());
            time.put("lastURL", harStat.getLastURL());
            time.put("totalDuration", harStat.getTimeTotalDuration());

            total.put("time", time);

            stat.put(statKey, total);

            return stat;

        } catch (JSONException ex) {
            LOG.error("Exception when trying to convert HarStat to JSONObject.", ex);
        } catch (Exception ex) {
            LOG.error("Exception when trying to convert HarStat to JSONObject.", ex);
        }
        return stat;
    }

    private String guessType(JSONObject entry) {
        try {
            JSONArray header = entry.getJSONObject("response").getJSONArray("headers");
            for (int i = 0; i < header.length(); i++) {
                if ("Content-Type".equalsIgnoreCase(header.getJSONObject(i).getString("name"))) {
                    String val = header.getJSONObject(i).getString("value");
                    if (val.toLowerCase().contains("application/javascript") || val.toLowerCase().contains("text/javascript") || val.toLowerCase().contains("application/x-javascript")) {
                        return "js";
                    }
                    if (val.toLowerCase().contains("text/css")) {
                        return "css";
                    }
                    if (val.toLowerCase().contains("text/html")) {
                        return "html";
                    }
                    if (val.toLowerCase().contains("image/")) {
                        return "img";
                    }
                    if (val.toLowerCase().contains("application/json")) {
                        return "content";
                    }
                    if (val.toLowerCase().contains("font/")) {
                        return "font";
                    }
                    break;
                }
            }
        } catch (JSONException ex) {
            LOG.error("Exception when trying to guess response type.", ex);
        }

        return "other";
    }

}
