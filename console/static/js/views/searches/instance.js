define([
    'views/searches/generic',
], function(Search) {
    return function(images) {
      return new Search(images, ['group_name', 'root_device_name', 'name', 'placement', 'owner_id', 'image_id'], {}, null);
    }
});
