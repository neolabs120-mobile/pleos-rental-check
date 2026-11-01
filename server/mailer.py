"""SMTP over TLS; explicit local outbox mode is available only for development."""
import json
import os
import smtplib
import ssl
from email.message import EmailMessage
from pathlib import Path
from uuid import uuid4


def configured_mailer(data_dir: Path):
    mode = os.environ.get("RENTAL_MAIL_MODE", "smtp")
    if mode == "file":
        outbox = data_dir / "mail-outbox"
        outbox.mkdir(mode=0o700, parents=True, exist_ok=True)
        def file_mail(email, purpose, code):
            path = outbox / (str(uuid4()) + ".json")
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                json.dump({"mode": "LOCAL DEVELOPMENT ONLY", "email": email, "purpose": purpose, "code": code}, stream)
        file_mail.delivery_mode = "file"
        return file_mail
    if mode != "smtp":
        raise ValueError("RENTAL_MAIL_MODE는 smtp 또는 file이어야 합니다.")
    host, sender = os.environ.get("SMTP_HOST"), os.environ.get("SMTP_FROM")
    if not host or not sender:
        return None
    tls_mode = os.environ.get("SMTP_TLS", "starttls")
    if tls_mode not in {"starttls", "ssl"}:
        raise ValueError("SMTP_TLS는 starttls 또는 ssl이어야 합니다.")
    port = int(os.environ.get("SMTP_PORT", "465" if tls_mode == "ssl" else "587"))
    def smtp_mail(email, purpose, code):
        message = EmailMessage()
        message["From"], message["To"] = sender, email
        message["Subject"] = "렌트체크 " + ("가입" if purpose == "signup" else "비밀번호 재설정") + " 확인번호"
        message.set_content(f"확인번호: {code}\n10분 안에 입력해 주세요. 본인이 요청하지 않았다면 이 메일을 무시해 주세요.")
        client = smtplib.SMTP_SSL(host, port, timeout=15, context=ssl.create_default_context()) if tls_mode == "ssl" else smtplib.SMTP(host, port, timeout=15)
        with client:
            if tls_mode == "starttls":
                client.ehlo()
                client.starttls(context=ssl.create_default_context())
                client.ehlo()
            if os.environ.get("SMTP_USER"):
                client.login(os.environ["SMTP_USER"], os.environ.get("SMTP_PASSWORD", ""))
            client.send_message(message)
    return smtp_mail
