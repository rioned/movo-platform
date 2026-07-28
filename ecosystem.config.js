module.exports = {
  apps: [{
    name: 'movo',
    script: './server.js',
    cwd: __dirname,
    instances: 1,
    autorestart: true,
    watch: false,
    max_memory_restart: '300M',
    env: {
      NODE_ENV: 'production',
      PORT: 3000,
      OTP_TEST_MODE: 'false'
    }
  }]
};
