package alin.android.alinos.manager;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

public class EventBus {
    private static EventBus instance;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private List<EventListener> listeners = new ArrayList<>();

    public interface EventListener {
        void onEvent(String eventType, Object data);
    }

    private EventBus() {}

    public static EventBus getInstance() {
        if (instance == null) {
            instance = new EventBus();
        }
        return instance;
    }

    public void register(EventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregister(EventListener listener) {
        listeners.remove(listener);
    }

    public void post(String eventType, Object data) {
        mainHandler.post(() -> {
            for (EventListener listener : new ArrayList<>(listeners)) {
                try {
                    listener.onEvent(eventType, data);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }



}