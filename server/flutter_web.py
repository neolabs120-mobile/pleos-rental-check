from fastapi.staticfiles import StaticFiles


def mount_flutter_web(app, directory):
    # Keep Flutter on the API origin and allow its selected local Blob files to be read by cross_file's XMLHttpRequest without permitting other network origins.
    app.mount("/mobile", StaticFiles(directory=directory, html=True), name="flutter-mobile")

    @app.middleware("http")
    async def flutter_response_headers(request, call_next):
        # Scope renderer and local-photo requirements to Flutter documents; account/API pages retain the application's stricter default policy.
        response = await call_next(request)
        if request.url.path.startswith("/mobile/"):
            response.headers["Content-Security-Policy"] = (
                "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; "
                "img-src 'self' blob: data:; font-src 'self' data: https://fonts.gstatic.com; "
                "connect-src 'self' blob: https://fonts.gstatic.com; worker-src 'self' blob:; "
                "frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
        return response
