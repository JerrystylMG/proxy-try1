/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.totp.user;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.totp.conf.ConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for tracking past valid uses of TOTP codes. An internal thread
 * periodically walks through records of past codes, removing records which
 * should be invalid by their own nature (no longer matching codes generated by
 * the secret key).
 */
@Singleton
public class CodeUsageTrackingService {

    /**
     * The number of periods during which a previously-used code should remain
     * unusable. Once this period has elapsed, the code can be reused again if
     * it is otherwise valid.
     */
    private static final int INVALID_INTERVAL = 2;

    /**
     * Logger for this class.
     */
    private final Logger logger = LoggerFactory.getLogger(CodeUsageTrackingService.class);

    /**
     * Executor service which runs the cleanup task.
     */
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    /**
     * Service for retrieving configuration information.
     */
    @Inject
    private ConfigurationService confService;

    /**
     * Map of previously-used codes to the timestamp after which the code can
     * be used again, providing the TOTP key legitimately generates that code.
     */
    private final ConcurrentMap<UsedCode, Long> invalidCodes =
            new ConcurrentHashMap<UsedCode, Long>();

    /**
     * Creates a new CodeUsageTrackingService which tracks past valid uses of
     * TOTP codes on a per-user basis.
     */
    public CodeUsageTrackingService() {
        executor.scheduleAtFixedRate(new CodeEvictionTask(), 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Task which iterates through all explicitly-invalidated codes, evicting
     * those codes which are old enough that they would fail validation against
     * the secret key anyway.
     */
    private class CodeEvictionTask implements Runnable {

        @Override
        public void run() {

            // Get start time of cleanup check
            long checkStart = System.currentTimeMillis();

            // For each code still being tracked, remove those which are old
            // enough that they would fail validation against the secret key
            Iterator<Map.Entry<UsedCode, Long>> entries = invalidCodes.entrySet().iterator();
            while (entries.hasNext()) {

                Map.Entry<UsedCode, Long> entry = entries.next();
                long invalidUntil = entry.getValue();

                // If code is sufficiently old, evict it and check the next one
                if (checkStart >= invalidUntil)
                    entries.remove();

            }

            // Log completion and duration
            logger.debug("TOTP tracking cleanup check completed in {} ms.",
                    System.currentTimeMillis() - checkStart);

        }

    }

    /**
     * A valid TOTP code which was previously used by a particular user.
     */
    private class UsedCode {

        /**
         * The username of the user which previously used this code.
         */
        private final String username;

        /**
         * The valid code given by the user.
         */
        private final String code;

        /**
         * Creates a new UsedCode which records the given code as having been
         * used by the given user.
         *
         * @param username
         *     The username of the user which previously used the given code.
         *
         * @param code
         *     The valid code given by the user.
         */
        public UsedCode(String username, String code) {
            this.username = username;
            this.code = code;
        }

        /**
         * Returns the username of the user which previously used the code
         * associated with this UsedCode.
         *
         * @return
         *     The username of the user which previously used this code.
         */
        public String getUsername() {
            return username;
        }

        /**
         * Returns the valid code given by the user when this UsedCode was
         * created.
         *
         * @return
         *     The valid code given by the user.
         */
        public String getCode() {
            return code;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 79 * hash + this.username.hashCode();
            hash = 79 * hash + this.code.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {

            if (this == obj)
                return true;

            if (obj == null)
                return false;

            if (getClass() != obj.getClass())
                return false;

            final UsedCode other = (UsedCode) obj;
            return username.equals(other.username) && code.equals(other.code);

        }

    }

    /**
     * Attempts to mark the given code as used. The code MUST have already been
     * validated against the user's secret key, as this function only verifies
     * whether the code has been previously used, not whether it is actually
     * valid. If the code has not previously been used, the code is stored as
     * having been used by the given user at the current time.
     *
     * @param username
     *     The username of the user who has attempted to use the given valid
     *     code.
     *
     * @param code
     *     The otherwise-valid code given by the user.
     *
     * @return
     *     true if the code has not previously been used by the given user and
     *     has now been marked as previously used, false otherwise.
     *
     * @throws GuacamoleException
     *     If configuration information necessary to determine the length of
     *     time a code should be marked as invalid cannot be read from
     *     guacamole.properties.
     */
    public boolean useCode(String username, String code)
            throws GuacamoleException {

        // Repeatedly attempt to use the given code until an explicit success
        // or failure has occurred
        UsedCode usedCode = new UsedCode(username, code);
        for (;;) {

            // Explicitly invalidate each used code for two periods after its
            // first successful use
            long current = System.currentTimeMillis();
            long invalidUntil = current + confService.getPeriod() * 1000 * INVALID_INTERVAL;

            // Try to use the given code, marking it as used within the map of
            // now-invalidated codes
            Long expires = invalidCodes.putIfAbsent(usedCode, invalidUntil);
            if (expires == null)
                return true;

            // If the code was already used, fail to use the code if
            // insufficient time has elapsed since it was last used
            // successfully
            if (expires > current)
                return false;


            // Otherwise, the code is actually valid - remove the invalidated
            // code only if it still has the expected expiration time, and
            // retry using the code
            invalidCodes.remove(usedCode, expires);

        }

    }

    /**
     * Cleans up resources which may be in use by this service in the
     * background, such as other threads. This function MUST be invoked during
     * webapp shutdown to avoid leaking these resources.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

}
