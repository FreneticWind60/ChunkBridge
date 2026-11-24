package com.frenetic.litechunk.sdk.internal;

import android.os.Bundle;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves a {@link LevelInfo} across the process boundary as nested {@link Bundle} values.
 * <p>
 * The api module is a plain Java library, so its model classes are not {@code Parcelable} and must not become so:
 * the engine has to keep compiling outside Android for the desktop tests. Nested Bundle lists are the smallest
 * representation that stays Parcelable and needs no AIDL.
 */
public final class LevelBundle {

    private static final String KEY_FORMAT_ID = "formatId";
    private static final String KEY_FORMAT_LABEL = "formatLabel";
    private static final String KEY_VERSION = "version";
    private static final String KEY_FIELDS = "fields";
    private static final String KEY_NOTES = "notes";

    private static final String KEY_ID = "id";
    private static final String KEY_ZH = "zh";
    private static final String KEY_EN = "en";
    private static final String KEY_VALUE = "value";
    private static final String KEY_KIND = "kind";
    private static final String KEY_OPTION_IDS = "optionIds";
    private static final String KEY_OPTION_ZH = "optionZh";
    private static final String KEY_OPTION_EN = "optionEn";
    private static final String KEY_HINT_ZH = "hintZh";
    private static final String KEY_HINT_EN = "hintEn";
    private static final String KEY_HINT_CODE = "hintCode";
    private static final String KEY_SEVERITY = "severity";
    private static final String KEY_TEXT_ZH = "textZh";
    private static final String KEY_TEXT_EN = "textEn";

    private LevelBundle() {
    }

    public static Bundle write(LevelInfo info) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_FORMAT_ID, info.formatId);
        bundle.putString(KEY_FORMAT_LABEL, info.formatLabel);
        bundle.putString(KEY_VERSION, info.version);
        ArrayList<Bundle> fields = new ArrayList<>(info.fields.size());
        for (LevelField field : info.fields) {
            fields.add(writeField(field));
        }
        bundle.putParcelableArrayList(KEY_FIELDS, fields);
        ArrayList<Bundle> notes = new ArrayList<>(info.notes.size());
        for (LevelNote note : info.notes) {
            Bundle entry = new Bundle();
            entry.putInt(KEY_SEVERITY, note.severity);
            entry.putString(KEY_TEXT_ZH, note.textZh);
            entry.putString(KEY_TEXT_EN, note.textEn);
            notes.add(entry);
        }
        bundle.putParcelableArrayList(KEY_NOTES, notes);
        return bundle;
    }

    public static LevelInfo read(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        List<LevelField> fields = new ArrayList<>();
        List<Bundle> storedFields = bundle.getParcelableArrayList(KEY_FIELDS);
        if (storedFields != null) {
            for (Bundle stored : storedFields) {
                LevelField field = readField(stored);
                if (field != null) {
                    fields.add(field);
                }
            }
        }
        List<LevelNote> notes = new ArrayList<>();
        List<Bundle> storedNotes = bundle.getParcelableArrayList(KEY_NOTES);
        if (storedNotes != null) {
            for (Bundle stored : storedNotes) {
                if (stored != null) {
                    notes.add(new LevelNote(stored.getInt(KEY_SEVERITY),
                            stored.getString(KEY_TEXT_ZH), stored.getString(KEY_TEXT_EN)));
                }
            }
        }
        return new LevelInfo(bundle.getString(KEY_FORMAT_ID), bundle.getString(KEY_FORMAT_LABEL),
                bundle.getString(KEY_VERSION), fields, notes);
    }

    private static Bundle writeField(LevelField field) {
        Bundle entry = new Bundle();
        entry.putString(KEY_ID, field.id);
        entry.putString(KEY_ZH, field.labelZh);
        entry.putString(KEY_EN, field.labelEn);
        entry.putString(KEY_VALUE, field.value);
        entry.putInt(KEY_KIND, field.kind);
        entry.putStringArray(KEY_OPTION_IDS, field.optionIds);
        entry.putStringArray(KEY_OPTION_ZH, field.optionZh);
        entry.putStringArray(KEY_OPTION_EN, field.optionEn);
        entry.putString(KEY_HINT_ZH, field.hintZh);
        entry.putString(KEY_HINT_EN, field.hintEn);
        entry.putString(KEY_HINT_CODE, field.hintCode);
        return entry;
    }

    private static LevelField readField(Bundle entry) {
        if (entry == null || entry.getString(KEY_ID) == null) {
            return null;
        }
        return new LevelField(entry.getString(KEY_ID), entry.getString(KEY_ZH), entry.getString(KEY_EN),
                entry.getString(KEY_VALUE), entry.getInt(KEY_KIND),
                entry.getStringArray(KEY_OPTION_IDS), entry.getStringArray(KEY_OPTION_ZH),
                entry.getStringArray(KEY_OPTION_EN), entry.getString(KEY_HINT_ZH), entry.getString(KEY_HINT_EN),
                entry.getString(KEY_HINT_CODE));
    }
}
