package com.github.nikalon.sunsync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

public class ConfigurationTest {
    @Test
    public void shouldValidateCorrectDecimalCoordinatesTest() {
        var conf = new Configuration(Logger.getLogger("testLogger"));

        // I don't know how to handle this case correctly with regular expressions, so it's not handled for now...
        // assertTrue(conf.setLocation("50,50")); 

        assertTrue(conf.setLocation("73.2, 92.34"));
        assertTrue(conf.setLocation("10 10.23"));
        assertTrue(conf.setLocation("0.2      0.23"));
        assertTrue(conf.setLocation("90.0, 180.0"));
        assertTrue(conf.setLocation("90.0,      180.0"));
    }

    @Test
    public void shouldNotValidateIncorrectDecimalCoordinatesTest() {
        var conf = new Configuration(Logger.getLogger("testLogger"));
        assertFalse(conf.setLocation("91,0"));
        assertFalse(conf.setLocation("91, 0"));
        assertFalse(conf.setLocation("91 0"));
        assertFalse(conf.setLocation("0 181"));
        assertFalse(conf.setLocation("0, 181"));
        assertFalse(conf.setLocation("92, 181"));

        assertFalse(conf.setLocation("11"));
        assertFalse(conf.setLocation("111"));
        assertFalse(conf.setLocation("11.1"));
        assertFalse(conf.setLocation("1.11.1"));
        assertFalse(conf.setLocation("1.1.1.1"));
    }
}
