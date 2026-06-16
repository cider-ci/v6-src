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

def tr_project(id)
  find("tr.project td.id", text: id).ancestor("tr")
end

# Force the server's in-memory repository state to reload from the DB.
# Required after clean_db because TRUNCATE+INSERT can race with the 1s daemon cycle.
def reload_server_repository_state
  require 'net/http'
  require 'uri'
  nrepl_port = File.read(File.join(PROJECT_DIR, '.nrepl-port')).strip.to_i
  code = '(cider-ci.server.projects.repositories.state.repositories/update-repositories)'
  msg  = "d2:id1:12:op4:eval4:code#{code.length}:#{code}e"
  sock = TCPSocket.new('localhost', nrepl_port)
  sock.write(msg)
  sock.read_nonblock(4096) rescue nil
  sock.close
rescue => e
  warn "reload_server_repository_state failed: #{e.message}"
end