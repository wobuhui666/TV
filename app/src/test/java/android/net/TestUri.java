package android.net;

import android.os.Parcel;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Concrete URI for JVM DataSource tests, where the Android Uri.parse stub returns null. */
public final class TestUri extends Uri {

    private final URI value;

    private TestUri(String value) {
        this.value = URI.create(value);
    }

    public static Uri of(String value) {
        return new TestUri(value);
    }

    @Override public String getScheme() { return value.getScheme(); }
    @Override public boolean isHierarchical() { return !value.isOpaque(); }
    @Override public boolean isRelative() { return !value.isAbsolute(); }
    @Override public String getSchemeSpecificPart() { return value.getSchemeSpecificPart(); }
    @Override public String getEncodedSchemeSpecificPart() { return value.getRawSchemeSpecificPart(); }
    @Override public String getAuthority() { return value.getAuthority(); }
    @Override public String getEncodedAuthority() { return value.getRawAuthority(); }
    @Override public String getUserInfo() { return value.getUserInfo(); }
    @Override public String getEncodedUserInfo() { return value.getRawUserInfo(); }
    @Override public String getHost() { return value.getHost(); }
    @Override public int getPort() { return value.getPort(); }
    @Override public String getPath() { return value.getPath(); }
    @Override public String getEncodedPath() { return value.getRawPath(); }
    @Override public String getQuery() { return value.getQuery(); }
    @Override public String getEncodedQuery() { return value.getRawQuery(); }
    @Override public String getFragment() { return value.getFragment(); }
    @Override public String getEncodedFragment() { return value.getRawFragment(); }
    @Override public String toString() { return value.toString(); }
    @Override public List<String> getPathSegments() {
        String path = getPath();
        if (path == null || path.isEmpty() || path.equals("/")) return Collections.emptyList();
        return Arrays.asList((path.startsWith("/") ? path.substring(1) : path).split("/"));
    }
    @Override public String getLastPathSegment() {
        List<String> segments = getPathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }
    @Override public Builder buildUpon() { throw new UnsupportedOperationException(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) { throw new UnsupportedOperationException(); }
}
