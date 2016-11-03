package com.kaltura.playkit;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Noam Tamim @ Kaltura on 18/09/2016.
 */
public class PlayerConfig {
    private boolean mAutoPlay = false;
    private long mStartPosition = 0;
    private PKMediaEntry mMediaEntry;
    private Map<String, JSONObject> pluginConfig = new HashMap<>();

    public boolean isAutoPlay() {
        return mAutoPlay;
    }

    public PlayerConfig setAutoPlay(boolean autoPlay) {
        this.mAutoPlay = autoPlay;
        return this;
    }

    public long getStartPosition() {
        return mStartPosition;
    }

    public PlayerConfig setStartPosition(long startPosition) {
        this.mStartPosition = startPosition;
        return this;
    }

    public PKMediaEntry getMediaEntry() {
        return mMediaEntry;
    }

    public PlayerConfig setMediaEntry(PKMediaEntry mediaEntry) {
        this.mMediaEntry = mediaEntry;
        return this;
    }

    public JSONObject getPluginConfig(String pluginName) {
        JSONObject jsonObject = pluginConfig.get(pluginName);
        if (jsonObject == null) {
            jsonObject = new JSONObject();
            pluginConfig.put(pluginName, jsonObject);
        }
        return jsonObject;
    }

    public PlayerConfig setPluginConfig(String pluginName, JSONObject config) {
        pluginConfig.put(pluginName, config);
        return this;
    }
    
    public Map<String, JSONObject> getPluginConfigMap() {
        return Collections.unmodifiableMap(pluginConfig);
    }
}
