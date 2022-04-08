module Helpers
  module Global
    extend self

    def wait_until(wait_time = 5, &block)
      Timeout.timeout(wait_time) do
        until value = yield
          sleep(0.2)
        end
        value
      end
    rescue Timeout::Error => e
      raise Timeout::Error.new(block.source)
    end

    def click_on_first(locator, options = {})
      wait_until(3) { first(:link_or_button, locator, options) }
      first(:link_or_button, locator, options).click
    end

    def set_session_cookie user
      visit '/' unless current_path.presence
      Capybara.current_session.driver.browser.manage.add_cookie(
        name: "cider-ci-session",
        value: user.session_token)
    end

  end
end
