package com.codex.desktreadmill.model;

public class UserProfile {
    public double weightKg = 70.0;
    public double heightCm = 170.0;
    public boolean completed = false;

    public UserProfile copy() {
        UserProfile copy = new UserProfile();
        copy.weightKg = weightKg;
        copy.heightCm = heightCm;
        copy.completed = completed;
        return copy;
    }

    public boolean isComplete() {
        return completed && weightKg > 0 && heightCm > 0;
    }
}
