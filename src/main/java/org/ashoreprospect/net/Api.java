/** App-wide Node session handle (Api.INSTANCE). */
package org.ashoreprospect.net;

public final class Api {
    public static final NodeApi INSTANCE = new NodeApi();
    private Api() {}
}