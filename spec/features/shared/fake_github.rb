require 'json'
require 'socket'

# A minimal fake of the GitHub statuses API: records every request and answers
# 201, or the statuses queued with `respond_next`. Plain TCPServer so the
# specs need no web server gem.
class FakeGithub
  Request = Struct.new(:method, :path, :headers, :body, keyword_init: true)

  attr_reader :port

  def initialize
    @server = TCPServer.new('127.0.0.1', 0)
    @port = @server.addr[1]
    @requests = []
    @responses = []
    @mutex = Mutex.new
    @thread = Thread.new { loop { handle(@server.accept) } }
  end

  def url
    "http://127.0.0.1:#{port}"
  end

  def requests
    @mutex.synchronize { @requests.dup }
  end

  def respond_next(status, body = {})
    @mutex.synchronize { @responses << [status, body] }
  end

  def stop
    @thread.kill
    @server.close
  end

  private

  def handle(client)
    method, path = client.gets.to_s.split(' ')
    headers = {}
    while (line = client.gets) && line != "\r\n"
      name, value = line.split(':', 2)
      headers[name.strip.downcase] = value.to_s.strip
    end
    raw = client.read(headers['content-length'].to_i)
    body = (JSON.parse(raw) rescue raw)
    status, response = @mutex.synchronize do
      @requests << Request.new(method: method, path: path, headers: headers, body: body)
      @responses.shift || [201, { id: @requests.size }]
    end
    payload = response.to_json
    client.write("HTTP/1.1 #{status} X\r\nContent-Type: application/json\r\n" \
                 "Content-Length: #{payload.bytesize}\r\nConnection: close\r\n\r\n#{payload}")
  rescue StandardError => e
    warn "FakeGithub: #{e.message}"
  ensure
    client&.close
  end
end

shared_context 'with fake github' do
  before(:each) { @fake_github = FakeGithub.new }
  after(:each) { @fake_github.stop }

  def github_requests
    @fake_github.requests
  end

  def wait_for_github_request(state, timeout: 15)
    wait_until(timeout) { github_requests.find { |r| r.body.is_a?(Hash) && r.body['state'] == state } }
  end
end
