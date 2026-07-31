/**
 * PM2 process configuration.
 *
 * A single instance is deliberate: better-sqlite3 is an in-process database, so
 * horizontal scaling means moving to a networked database first, not adding
 * cluster workers here. `kill_timeout` gives the graceful shutdown path time to
 * drain sockets and checkpoint the WAL before the process is terminated.
 */
module.exports = {
  apps: [{
    name: 'movo',
    script: './server.js',
    cwd: __dirname,
    instances: 1,
    exec_mode: 'fork',
    autorestart: true,
    watch: false,
    max_memory_restart: '400M',
    kill_timeout: 12000,
    listen_timeout: 10000,
    wait_ready: false,
    time: true,
    merge_logs: true,
    env: {
      NODE_ENV: 'production',
      PORT: 3000,
      OTP_TEST_MODE: 'false',
      TRUST_PROXY: 'true',
      HTTPS_ONLY: 'true',
      RATE_LIMIT_ENABLED: 'true',
      LOG_LEVEL: 'info',
      LIVE_MAP_DEMO_MODE: 'false'
    }
  }]
};
