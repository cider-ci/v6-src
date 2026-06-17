require 'spec_helper'
require 'net/http'
require 'digest'
require 'json'
require 'securerandom'

feature 'Admin: Executor management' do

  before :each do
    @admin = FactoryBot.create(:admin)
    @user  = FactoryBot.create(:user)
  end

  def admin_request(method, path, body = nil)
    uri = URI("#{http_base_url}#{path}")
    req = case method
          when :get    then Net::HTTP::Get.new(uri)
          when :post   then Net::HTTP::Post.new(uri)
          when :patch  then Net::HTTP::Patch.new(uri)
          when :delete then Net::HTTP::Delete.new(uri)
          end
    req['Cookie']       = "cider-ci-session=#{@admin.session_token}"
    req['Content-Type'] = 'application/json'
    req['Accept']       = 'application/json'
    req.body = body.to_json if body
    res = Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }
    body_parsed = JSON.parse(res.body) rescue res.body
    [res.code.to_i, body_parsed]
  end

  scenario 'lists executors (empty initially)' do
    status, body = admin_request(:get, '/admin/executors/')
    expect(status).to eq 200
    expect(body).to eq []
  end

  scenario 'non-admin user gets 403' do
    uri = URI("#{http_base_url}/admin/executors/")
    req = Net::HTTP::Get.new(uri)
    req['Cookie'] = "cider-ci-session=#{@user.session_token}"
    req['Accept'] = 'application/json'
    res = Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }
    expect(res.code.to_i).to eq 403
  end

  scenario 'creates an executor and returns a one-time token' do
    status, body = admin_request(:post, '/admin/executors/',
      { name: 'my-executor', traits: 'Bash, Ruby', max_load: 8.0 })

    expect(status).to eq 200
    expect(body['token']).not_to be_nil
    expect(body['executors'].length).to eq 1
    expect(body['executors'].first['name']).to eq 'my-executor'
    expect(body['executors'].first['token_hash']).to be_nil
  end

  scenario 'token hash in DB matches SHA-256 of returned token' do
    _, body = admin_request(:post, '/admin/executors/',
      { name: 'hash-check', traits: '', max_load: 4.0 })

    token = body['token']
    executor = database[:executors].where(token_part: token[0..7]).first
    expect(executor[:token_hash]).to eq Digest::SHA256.hexdigest(token)
  end

  scenario 'enable/disable an executor via PATCH' do
    _, create_body = admin_request(:post, '/admin/executors/',
      { name: 'toggle-test', traits: '', max_load: 4.0 })
    executor_id = create_body['executors'].first['id']

    status, body = admin_request(:patch, "/admin/executors/#{executor_id}", { enabled: false })
    expect(status).to eq 200
    expect(body.first['enabled']).to eq false

    status, body = admin_request(:patch, "/admin/executors/#{executor_id}", { enabled: true })
    expect(status).to eq 200
    expect(body.first['enabled']).to eq true
  end

  scenario 'deletes an executor' do
    _, create_body = admin_request(:post, '/admin/executors/',
      { name: 'to-delete', traits: '', max_load: 4.0 })
    executor_id = create_body['executors'].first['id']

    status, body = admin_request(:delete, "/admin/executors/#{executor_id}")
    expect(status).to eq 200
    expect(body).to eq []
    expect(database[:executors].where(id: executor_id).count).to eq 0
  end

end
