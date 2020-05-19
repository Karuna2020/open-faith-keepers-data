#!/bin/bash

set -eo pipefail

sudo DEBIAN_FRONTEND=noninteractive apt-get install -qq \
  xfonts-base \
  xfonts-75dpi \
  < /dev/null > /dev/null

# The canonical URL is on wkhtmltopdf.org, but its cert has expired so we
# directly use the GitHub one that is redirected to anyway:
# https://downloads.wkhtmltopdf.org/0.12/0.12.5/wkhtmltox_0.12.5-1.bionic_amd64.deb"
WKHTMLTOPDF_URL="https://github.com/wkhtmltopdf/wkhtmltopdf/releases/download/0.12.5/wkhtmltox_0.12.5-1.bionic_amd64.deb"
WKHTMLTOPDF_SHA256="db48fa1a043309c4bfe8c8e0e38dc06c183f821599dd88d4e3cea47c5a5d4cd3"

cd /tmp
curl --silent --show-error --location --max-redirs 2 --fail --retry 3 --output wkhtmltopdf-linux-amd64.deb $WKHTMLTOPDF_URL
echo "$WKHTMLTOPDF_SHA256 wkhtmltopdf-linux-amd64.deb" | sha256sum -c -
sudo dpkg -i wkhtmltopdf-linux-amd64.deb
rm -rf wkhtmltopdf-linux-amd64.deb
wkhtmltopdf -V
