#!/usr/bin/env bash
# ==============================================================================
# Open Jarvis 24/7 VPS Deployment Script (Ubuntu / Debian Linux)
# Installs Open Jarvis backend, sets up Python virtual environment & systemd service.
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}${BOLD}"
echo "================================================================"
echo "          J.A.R.V.I.S. 24/7 Linux VPS Deployment                "
echo "================================================================"
echo -e "${NC}"

# 1. Require root privileges
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}[ERROR] Please run this script as root or with sudo:${NC}"
  echo "  sudo bash deploy_vps.sh"
  exit 1
fi

INSTALL_DIR="/opt/openjarvis"
SERVICE_NAME="openjarvis"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
PORT=8765

echo -e "${GREEN}[1/6] Installing system packages & audio codecs (flac, ffmpeg)...${NC}"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-pip python3-venv git curl flac ffmpeg

echo -e "${GREEN}[2/6] Setting up project directory in ${INSTALL_DIR}...${NC}"
mkdir -p "${INSTALL_DIR}"

# If running inside a git checkout, copy files; otherwise clone from GitHub
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "${CURRENT_DIR}/pyproject.toml" ] && [ -d "${CURRENT_DIR}/src" ]; then
    echo "Copying files from ${CURRENT_DIR} to ${INSTALL_DIR}..."
    cp -r "${CURRENT_DIR}/"* "${INSTALL_DIR}/"
else
    echo "Cloning latest open-jarvis from GitHub..."
    if [ ! -d "${INSTALL_DIR}/.git" ]; then
        git clone https://github.com/sajjadyarmahdi14-dev/open-jarvis.git "${INSTALL_DIR}"
    else
        cd "${INSTALL_DIR}" && git pull origin main
    fi
fi

cd "${INSTALL_DIR}"

echo -e "${GREEN}[3/6] Creating Python virtual environment...${NC}"
python3 -m venv "${INSTALL_DIR}/venv"
"${INSTALL_DIR}/venv/bin/pip" install --upgrade pip setuptools wheel

echo -e "${GREEN}[4/6] Installing Python dependencies (fastapi, uvicorn, edge-tts, speech_recognition)...${NC}"
"${INSTALL_DIR}/venv/bin/pip" install \
    "fastapi>=0.110" \
    "uvicorn>=0.28" \
    "edge-tts>=6.1.12" \
    "SpeechRecognition>=3.17.0" \
    "httpx>=0.27" \
    "pydantic>=2.0" \
    "aiohttp>=3.9" \
    "python-dotenv>=1.0" \
    "rich>=13" \
    "click>=8" \
    "standard-aifc>=3.13.0" \
    "audioop-lts>=0.2.2"

# Install editable project package into venv
if [ -f "${INSTALL_DIR}/pyproject.toml" ]; then
    "${INSTALL_DIR}/venv/bin/pip" install -e "${INSTALL_DIR}" || true
fi

# Ensure .openjarvis directory and config exist
CONFIG_DIR="/root/.openjarvis"
mkdir -p "${CONFIG_DIR}"
if [ ! -f "${CONFIG_DIR}/config.json" ]; then
    cat <<EOF > "${CONFIG_DIR}/config.json"
{
  "active_model": "google/gemma-4-31b-it:free",
  "openrouter_model": "google/gemma-4-31b-it:free",
  "gemini_model": "gemini-2.5-flash"
}
EOF
fi

if [ ! -f "${CONFIG_DIR}/.env" ] && [ -f "${INSTALL_DIR}/.env" ]; then
    cp "${INSTALL_DIR}/.env" "${CONFIG_DIR}/.env"
fi

echo -e "${GREEN}[5/6] Creating permanent 24/7 systemd service (${SERVICE_FILE})...${NC}"
cat <<EOF > "${SERVICE_FILE}"
[Unit]
Description=Open Jarvis 24/7 AI Backend Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${INSTALL_DIR}
Environment="PYTHONPATH=${INSTALL_DIR}/src"
Environment="PATH=${INSTALL_DIR}/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
ExecStart=${INSTALL_DIR}/venv/bin/python -c "from openjarvis.web.server import run_server; run_server(host='0.0.0.0', port=${PORT})"
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

echo -e "${GREEN}[6/6] Enabling and starting ${SERVICE_NAME} service...${NC}"
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"

# Detect Public IP
PUBLIC_IP=$(curl -s --max-time 4 https://api.ipify.org || curl -s --max-time 4 https://ifconfig.me || hostname -I | awk '{print $1}')

echo ""
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo -e "${GREEN}${BOLD}     J.A.R.V.I.S. VPS BACKEND SUCCESSFULLY DEPLOYED (24/7)      ${NC}"
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo ""
echo -e "Service Status : ${CYAN}systemctl status ${SERVICE_NAME}${NC}"
echo -e "Service Logs   : ${CYAN}journalctl -u ${SERVICE_NAME} -f${NC}"
echo -e "Bound Address  : ${BOLD}http://0.0.0.0:${PORT}${NC}"
echo ""
echo -e "${YELLOW}${BOLD}>> HOW TO CONNECT YOUR ANDROID PHONE:${NC}"
echo -e "1. Open the ${BOLD}J.A.R.V.I.S. Android App${NC} on your phone."
echo -e "2. Tap the ${BOLD}⚙ Settings${NC} button in the top bar."
echo -e "3. In ${BOLD}Backend Server URL${NC}, enter:"
echo -e "   ${CYAN}${BOLD}http://${PUBLIC_IP}:${PORT}${NC}"
echo -e "4. Tap ${BOLD}Save${NC}. Your phone will now communicate with your VPS 24/7!"
echo ""
