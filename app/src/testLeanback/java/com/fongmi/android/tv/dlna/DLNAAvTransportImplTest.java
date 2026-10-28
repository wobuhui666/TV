package com.fongmi.android.tv.dlna;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DLNAAvTransportImplTest {

    @Test
    public void shouldReportWholeSpeedsAsIntegers() {
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(1f));
        assertEquals("2", DLNAAvTransportImpl.formatSpeed(2f));
        assertEquals("5", DLNAAvTransportImpl.formatSpeed(5f));
    }

    @Test
    public void shouldReportQuarterSpeedsAsReducedFractions() {
        assertEquals("1/4", DLNAAvTransportImpl.formatSpeed(0.25f));
        assertEquals("1/2", DLNAAvTransportImpl.formatSpeed(0.5f));
        assertEquals("3/4", DLNAAvTransportImpl.formatSpeed(0.75f));
        assertEquals("5/4", DLNAAvTransportImpl.formatSpeed(1.25f));
        assertEquals("3/2", DLNAAvTransportImpl.formatSpeed(1.5f));
        assertEquals("7/4", DLNAAvTransportImpl.formatSpeed(1.75f));
        assertEquals("9/2", DLNAAvTransportImpl.formatSpeed(4.5f));
    }

    @Test
    public void shouldPreserveDecimalSpeedsWithoutRoundingToIntegers() {
        assertEquals("11/10", DLNAAvTransportImpl.formatSpeed(1.1f));
        assertEquals("101/100", DLNAAvTransportImpl.formatSpeed(1.01f));
        assertEquals("20001/10000", DLNAAvTransportImpl.formatSpeed(2.0001f));
        assertEquals("1/10000", DLNAAvTransportImpl.formatSpeed(0.0001f));
        assertEquals("1000", DLNAAvTransportImpl.formatSpeed(1000f));
    }

    @Test
    public void shouldFallBackToNormalSpeedForInvalidValues() {
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(0f));
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(-1f));
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(Float.NaN));
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(Float.POSITIVE_INFINITY));
        assertEquals("1", DLNAAvTransportImpl.formatSpeed(Float.NEGATIVE_INFINITY));
    }
}
