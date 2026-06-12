package dev.nikhey.betternotes.alert;

/** Receives note/watchlist alerts (alert note created, watchlisted player joined, ...). */
public interface AlertSink {

    void send(String title, String detail, int color);
}
