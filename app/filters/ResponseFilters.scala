package filters

import play.api.Logger
import play.api.http.HeaderNames
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._

/**
 * Sends the researcher's session cookie (PLAY_SESSION) with SameSite=Lax, so pages of another site,
 * and participant pages running in an opaque origin (a researcher's sandboxed preview), can no longer
 * make requests that carry the researcher's sign-in. Participants never need it: their seats have
 * cookies of their own (security.ParticipantAuth), which keep their attributes. Registered in
 * Global.filters().
 */
class SessionCookieSameSite extends EssentialFilter {
  def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    next(request).map(result => SessionCookieSameSite.rewrite(request, result))
  }
}

object SessionCookieSameSite {

  val Attribute = "SameSite=Lax"

  /** The researcher's consoles: the new one, and the previous one. */
  val ConsolePages = Set("/", "/legacy-admin")

  /**
   * As rewrite(result), for the response to this request. Loading a console also sends the request's
   * sign-in back, with the attribute: a sign-in from before the attribute was set keeps going out
   * without it until the next sign-in, and Firefox and Safari then send it from other sites and from
   * sandboxed previews too.
   */
  def rewrite(request: RequestHeader, result: SimpleResult): SimpleResult = {
    if (!request.flash.isEmpty) {
      // After the filters, Play's server clears a flash cookie the request carries (one of another Play
      // application on this host, say) with a Set-Cookie header of its own, and it sends only one of
      // that header and "set-cookie": to it they are the same name. Left as Play wrote it, the session
      // cookie goes out, without the attribute this once, rather than not at all.
      result
    } else {
      rewrite(refreshed(request, result))
    }
  }

  // The result of loading a console, with the request's sign-in in it, when it sets no cookie itself.
  private def refreshed(request: RequestHeader, result: SimpleResult): SimpleResult = {
    val headers = result.header.headers
    // (A Java action's result has a Set-Cookie header even when it sets no cookie: an empty one.)
    val setsNone = headers.get(HeaderNames.SET_COOKIE).forall(Cookies.decode(_).isEmpty) && !headers.contains(RawSetCookie)
    if (request.method == "GET" && ConsolePages.contains(request.path) && setsNone && stillSignedIn(request)) {
      result.withHeaders(HeaderNames.SET_COOKIE -> Cookies.encode(Seq(Session.encodeAsCookie(request.session))))
    } else {
      result
    }
  }

  // Only a sign-in that is still the account's: one replaced meanwhile (a sign-in elsewhere, a sign-out
  // or a password change in another tab) must not be sent back over its replacement.
  private def stillSignedIn(request: RequestHeader): Boolean =
    request.session.get("uid").exists(uid => models.User.findByUID(uid) != null)

  /**
   * The header key the session cookie is sent under. Play 2.2 (2.2.0 is what Breadboard uses) can't set
   * SameSite, and appending it to the Set-Cookie header is not enough: its server reads that header
   * back into cookies and writes each one out again (NettyResultStreamer.createNettyResponse), which
   * makes "SameSite=Lax" a cookie of its own and drops it from the session cookie. A header whose name
   * is spelled differently is sent as it is, and header names are case-insensitive in HTTP. Only one
   * line can be sent that way (to the server too the two spellings are one name: a Set-Cookie header
   * beside it replaces it), so this is done when the session cookie is the response's only cookie,
   * which is so for every response that sets it (sign-in, sign-out, password change, a console load).
   */
  val RawSetCookie = "set-cookie"

  /** The result with its session cookie, if that is its only cookie, sent with SameSite=Lax (once). */
  def rewrite(result: SimpleResult): SimpleResult = {
    val headers = result.header.headers
    headers.get(HeaderNames.SET_COOKIE) match {
      case Some(value) =>
        val cookies = Cookies.decode(value)
        if (cookies.size == 1 && cookies.head.name == Session.COOKIE_NAME && !headers.contains(RawSetCookie)) {
          // Written as the server would write it, plus the attribute.
          val line = Cookies.encode(cookies) + "; " + Attribute
          result.copy(header = result.header.copy(headers = headers - HeaderNames.SET_COOKIE + (RawSetCookie -> line)))
        } else {
          if (cookies.exists(_.name == Session.COOKIE_NAME)) {
            Logger.warn("The session cookie went out without SameSite: the response sets other cookies too")
          }
          result
        }
      case None => result
    }
  }
}

/**
 * Lets participant pages that run in an opaque origin (a researcher's sandboxed preview) load the
 * static files under /assets that are fetched in CORS mode, such as fonts and the client's code
 * chunks. They are public files, so any origin may read them; no credentials are involved.
 */
class AssetsCors extends EssentialFilter {
  def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    if (AssetsCors.applies(request.path)) next(request).map(AssetsCors.allowAnyOrigin) else next(request)
  }
}

object AssetsCors {

  def applies(path: String): Boolean = path.startsWith("/assets/")

  def allowAnyOrigin(result: SimpleResult): SimpleResult = result.withHeaders("Access-Control-Allow-Origin" -> "*")
}
