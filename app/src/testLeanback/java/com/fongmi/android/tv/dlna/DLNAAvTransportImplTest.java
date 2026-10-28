package com.fongmi.android.tv.dlna;

import org.junit.Test;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.support.model.TransportState;

import static org.junit.Assert.assertEquals;

public class DLNAAvTransportImplTest {

    private static final UnsignedIntegerFourBytes INSTANCE_ID = new UnsignedIntegerFourBytes(0);

    @Test
    public void shouldReportDefaultTransportInfoWithoutAPlayer() {
        DLNAAvTransportImpl transport = new DLNAAvTransportImpl(null);

        assertEquals("1", transport.getTransportInfo(INSTANCE_ID).getCurrentSpeed());
        assertEquals(TransportState.NO_MEDIA_PRESENT, transport.getTransportInfo(INSTANCE_ID).getCurrentTransportState());
    }

    @Test
    public void shouldReadCachedPositionWithoutAPlayer() {
        DLNAAvTransportImpl transport = new DLNAAvTransportImpl(null);
        transport.updatePositionCache(15000, 120000);

        assertEquals("00:00:15", transport.getPositionInfo(INSTANCE_ID).getRelTime());
        assertEquals("00:02:00", transport.getPositionInfo(INSTANCE_ID).getTrackDuration());
        assertEquals("1", transport.getTransportInfo(INSTANCE_ID).getCurrentSpeed());
    }

    @Test
    public void shouldResetCachedPositionAndTransportState() {
        DLNAAvTransportImpl transport = new DLNAAvTransportImpl(null);
        transport.updatePositionCache(15000, 120000);
        transport.fireStateChange(RenderState.PLAYING);

        transport.reset();

        assertEquals("00:00:00", transport.getMediaInfo(INSTANCE_ID).getMediaDuration());
        assertEquals("1", transport.getTransportInfo(INSTANCE_ID).getCurrentSpeed());
        assertEquals(TransportState.NO_MEDIA_PRESENT, transport.getTransportInfo(INSTANCE_ID).getCurrentTransportState());
    }

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
