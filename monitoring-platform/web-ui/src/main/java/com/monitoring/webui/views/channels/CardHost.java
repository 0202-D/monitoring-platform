package com.monitoring.webui.views.channels;

import com.monitoring.webui.client.NotificationApiClient;

/** То, что карточка канала просит у страницы, на которой она лежит. */
public interface CardHost {

    NotificationApiClient api();

    /** Перечитать состояние всех каналов и обновить карточки. */
    void refresh();

    void success(String message);

    void error(String message);
}
