package com.ghostyapps.heybattery;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.ImageView;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageView backButton = findViewById(R.id.aboutBackButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // Enable back navigation via system back; no toolbar required.
    }
}