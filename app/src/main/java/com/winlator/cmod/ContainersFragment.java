package com.winlator.cmod;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.ui.settings.ContainersSettingsActivity;

public class ContainersFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return new FrameLayout(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            startActivity(new Intent(getActivity(), ContainersSettingsActivity.class));
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToMainDestination(R.id.main_menu_shortcuts);
            }
        }
    }
}
