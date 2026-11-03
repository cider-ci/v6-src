require 'base64'
require 'net/http'

# A 1x1 red PNG, the avatar the fake GitHub serves (github_sign_in_spec).
FAKE_AVATAR_PNG = Base64.decode64(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=='
).b

# The session cookie of the current browser session, for raw HTTP requests
# (images are not fetched through Capybara).
def session_cookie_header
  cookie = Capybara.current_session.driver.browser.manage.cookie_named('cider-ci-session')
  "cider-ci-session=#{cookie[:value]}"
end

def http_get(path, cookie: session_cookie_header, headers: {})
  Net::HTTP.get_response(URI("#{http_base_url}#{path}"), { 'Cookie' => cookie }.merge(headers))
end
