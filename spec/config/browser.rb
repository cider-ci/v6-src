require 'capybara/rspec'
require 'selenium-webdriver'


BROWSER_DOWNLOAD_DIR= File.absolute_path(File.expand_path(__FILE__)  + "../../../tmp")

def http_port
  @port ||= Integer(ENV['CIDER_CI_HTTP_PORT'].presence || 3838)
end


def http_host
  @host ||= ENV['CIDER_CI_HTTP_HOST'].presence || 'localhost'
end


def http_base_url
  @http_base_url ||= "http://#{http_host}:#{http_port}"
end

def set_capybara_values
  Capybara.app_host = http_base_url
  Capybara.server_port = http_port
end

Capybara.register_driver :firefox do |app|
end



RSpec.configure do |config|
  set_capybara_values

  # Capybara.run_server = false
  Capybara.default_driver = :firefox
  Capybara.current_driver = :firefox


  config.before :all do
    set_capybara_values
  end

  config.before :each do |example|
    set_capybara_values
  end

  config.after(:each) do |example|
    unless example.exception.nil?
      take_screenshot screenshot_dir
    end
  end

  config.before :all do
    FileUtils.remove_dir(screenshot_dir, force: true)
    FileUtils.mkdir_p(screenshot_dir)
  end

  def screenshot_dir
    Pathname(BROWSER_DOWNLOAD_DIR).join('screenshots')
  end

  def take_screenshot(screenshot_dir = nil, name = nil)
    name ||= "#{Time.now.iso8601.tr(':', '-')}.png"
    path = screenshot_dir.join(name)
    case Capybara.current_driver
    when :firefox
      page.driver.browser.save_screenshot(path) rescue nil
    when :poltergeist
      page.driver.render(path, full: true) rescue nil
    else
      Logger.warn "Taking screenshots is not implemented for \
              #{Capybara.current_driver}."
    end
  end
end
