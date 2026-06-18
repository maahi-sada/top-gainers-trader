"""
Fyers Auth — Step 1: Generate login URL.

Run this first:
    python auth/step1_get_login_url.py

Open the printed URL in your browser, log in, approve.
You'll land on a page (or get an error page — that's fine) with a URL
in the address bar containing `auth_code=...`. Copy that FULL URL.

Then run step 2 with that URL to get your access token.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from fyers_apiv3 import fyersModel
from auth.env_utils import load_env_value


def main():
    app_id       = load_env_value("FYERS_APP_ID")
    secret_id    = load_env_value("FYERS_SECRET_ID")
    redirect_uri = load_env_value("FYERS_REDIRECT_URI")

    if not app_id or not secret_id:
        print("ERROR: FYERS_APP_ID / FYERS_SECRET_ID missing in .env")
        sys.exit(1)

    session = fyersModel.SessionModel(
        client_id=app_id,
        secret_key=secret_id,
        redirect_uri=redirect_uri,
        response_type="code",
        grant_type="authorization_code"
    )
    login_url = session.generate_authcode()

    print("=" * 70)
    print("STEP 1 — OPEN THIS URL IN YOUR BROWSER:")
    print("=" * 70)
    print(login_url)
    print("=" * 70)
    print("\nAfter logging in and approving, copy the FULL redirected URL")
    print("from your browser's address bar.")
    print("\nThen run:")
    print('  python auth/step2_get_token.py "PASTE_FULL_REDIRECT_URL_HERE"')


if __name__ == "__main__":
    main()
