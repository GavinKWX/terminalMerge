package com.sc.mf919.java.activity;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.sc.mf919.R;

public class ParameterValueEditor {

    private final String tag;
    private final EditText valueEt;
    private final View mView;

    public ParameterValueEditor(
            Activity context,
            int id,
            String tag,
            String value) {

        this.tag = tag;

        mView = context.getLayoutInflater()
                .inflate(R.layout.activity_parameter_tag_value_edit, null, true);

        mView.setId(id);

        ((TextView)mView.findViewById(R.id.tag)).setText(tag);

        valueEt = mView.findViewById(R.id.value);
        valueEt.setText(value);
    }

    public String getTag() {
        return tag;
    }

    public String getValue() {
        return valueEt.getText().toString();
    }

    public View getView() {
        return mView;
    }
}
