package com.mcdyc.infinitycell.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Log {
    private static final Logger LOGGER = LogManager.getLogger("InfinityCell");

    public static void info(String format, Object... data) {
        LOGGER.info(String.format(format, data));
    }

    public static void debug(String format, Object... data) {
        LOGGER.debug(String.format(format, data));
    }

    public static void warn(String format, Object... data) {
        LOGGER.warn(String.format(format, data));
    }

    public static void error(String format, Object... data) {
        LOGGER.error(String.format(format, data));
    }

    public static void error(String message, Throwable t) {
        LOGGER.error(message, t);
    }
}
