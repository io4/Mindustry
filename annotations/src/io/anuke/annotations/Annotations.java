package io.anuke.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Goal: To create a system to send events to the server from the client and vice versa, without creating a new packet type each time.<br>
 * These events may optionally also trigger on the caller client/server as well.<br>
 */
public class Annotations{

    /** Marks a class as serializable.*/
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.CLASS)
    public @interface Serialize{

    }

    public enum PacketPriority{
        /** Gets put in a queue and processed if not connected. */
        normal,
        /** Gets handled immediately, regardless of connection status. */
        high,
        /** Does not get handled unless client is connected. */
        low
    }

    /** A set of two booleans, one specifying server and one specifying client. */
    public enum Loc{
        /** Method can only be invoked on the client from the server. */
        server(true, false),
        /** Method can only be invoked on the server from the client. */
        client(false, true),
        /** Method can be invoked from anywhere */
        both(true, true),
        /** Neither server nor client. */
        none(false, false);

        /** If true, this method can be invoked ON clients FROM servers. */
        public final boolean isServer;
        /** If true, this method can be invoked ON servers FROM clients. */
        public final boolean isClient;

        Loc(boolean server, boolean client){
            this.isServer = server;
            this.isClient = client;
        }
    }

    public enum Variant{
        /** Method can only be invoked targeting one player. */
        one(true, false),
        /** Method can only be invoked targeting all players. */
        all(false, true),
        /** Method targets both one player and all players. */
        both(true, true);

        public final boolean isOne, isAll;

        Variant(boolean isOne, boolean isAll){
            this.isOne = isOne;
            this.isAll = isAll;
        }
    }

    /** Marks a method as invokable remotely across a server/client connection. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface Remote{
        /** Specifies the locations from which this method can be invoked. */
        Loc targets() default Loc.server;

        /** Specifies which methods are generated. Only affects server-to-client methods. */
        Variant variants() default Variant.all;

        /** The local locations where this method is called locally, when invoked. */
        Loc called() default Loc.none;

        /** Whether to forward this packet to all other clients upon recieval. Client only. */
        boolean forward() default false;

        /**
         * Whether the packet for this method is sent with UDP instead of TCP.
         * UDP is faster, but is prone to packet loss and duplication.
         */
        boolean unreliable() default false;

        /** Priority of this event. */
        PacketPriority priority() default PacketPriority.normal;
    }

    /**
     * Specifies that this method will be used to write classes of the type returned by {@link #value()}.<br>
     * This method must return void and have two parameters, the first being of type {@link java.nio.ByteBuffer} and the second
     * being the type returned by {@link #value()}.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface WriteClass{
        Class<?> value();
    }

    /**
     * Specifies that this method will be used to read classes of the type returned by {@link #value()}. <br>
     * This method must return the type returned by {@link #value()},
     * and have one parameter, being of type {@link java.nio.ByteBuffer}.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface ReadClass{
        Class<?> value();
    }
}
