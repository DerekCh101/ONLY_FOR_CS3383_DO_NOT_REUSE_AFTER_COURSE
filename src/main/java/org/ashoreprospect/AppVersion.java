/** Product version shown on loading and account screens. */
package org.ashoreprospect;

public final class AppVersion {

    /**
     * The release tag this build corresponds to, so the UI and the GitHub release cannot drift
     * apart. Keep this in step with {@code version} in build.gradle.kts when cutting a release.
     */
    public static final String LABEL = "v0.1.7";

    private AppVersion() {}
}
