package com.sysadminanywhere.m3.base.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

public final class SaveErrors {
    private SaveErrors() {}
    public static String message(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if (cause instanceof org.springframework.dao.OptimisticLockingFailureException || cause instanceof jakarta.persistence.OptimisticLockException)
                return t("Configuration changed in another session. Reload before saving.");
        }
        return t(error.getMessage()==null ? "Could not save changes" : error.getMessage());
    }
}
